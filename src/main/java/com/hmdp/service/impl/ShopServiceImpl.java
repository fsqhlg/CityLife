package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisBloomFilter;
import com.hmdp.utils.SystemConstants;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisBloomFilter redisBloomFilter;

    @Resource
    private MeterRegistry meterRegistry;

    /**
     * 逻辑过期方案中，重建缓存用的线程池（异步更新，不阻塞读请求）
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 释放锁的 Lua：value 对得上才删，解决“锁 TTL 到期后误删别人的锁”。
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result queryById(Long id) {
        // 0. 布隆过滤器先挡非法 id：不存在直接返回，不查缓存、不打 DB
        if (!redisBloomFilter.mightContain(id)) {
            meterRegistry.counter("shop_bloom_blocked_total").increment();
            return Result.fail(ErrorCode.NOT_FOUND, "店铺不存在！");
        }

        // 方案一：空值缓存解决缓存穿透（基础版，没有处理击穿）
        // Shop shop = queryWithPassThrough(id);

        // 方案二：互斥锁解决缓存击穿（默认方案：不需要预热，开箱即用）
        Shop shop = queryWithMutex(id);

        // 方案三：逻辑过期解决缓存击穿（需要先把热点 key 预热到 Redis，见 saveShop2Redis）
        // 体验方式：先执行测试类里的 testSaveShop2Redis 预热，再把上面一行换成下面这行
        // Shop shop = queryWithLogicalExpire(id);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    /**
     * 方案一：缓存穿透
     * <p>
     * 查询顺序：Redis -> 命中直接返回；
     * 未命中且是空串（说明之前缓存过"不存在"）-> 直接返回 null；
     * 否则查数据库：不存在则缓存空串（短 TTL），存在则写缓存。
     */
    private Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;

        // 1. 从 Redis 查缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        // 2. 命中真实数据，直接反序列化返回
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 3. 命中空字符串：说明数据库里也没有这家店，直接返回 null，避免缓存穿透
        if (shopJson != null) {
            return null;
        }

        // 4. 未命中，查数据库
        Shop shop = getById(id);

        // 5. 数据库不存在：缓存空值，TTL 较短，防止同一个不存在的 id 反复打库
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        // 6. 数据库存在：写入缓存，TTL 加随机值防止缓存雪崩
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), randomTtl(), TimeUnit.MINUTES);
        return shop;
    }

    /**
     * 方案二：互斥锁解决缓存击穿
     * <p>
     * 缓存未命中时，只让抢到锁（SETNX）的那个线程去查数据库并重建缓存，
     * 其他线程睡眠一小段时间后重试，从而保护数据库不被热点 key 击穿。
     */
    private Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;

        // 1. 查缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null) {
            // 空字符串：缓存了"店铺不存在"
            return null;
        }

        // 2. 未命中，尝试获取互斥锁（带 UUID value，释放时校验，防止误删）
        String lockKey = LOCK_SHOP_KEY + id;
        String lockValue;
        Shop shop;
        int retries = 0;
        while (true) {
            lockValue = tryLock(lockKey);
            if (lockValue != null) {
                break;
            }
            retries++;
            // 最多等 1.5 秒，超时直接返回，避免请求线程无限堆积
            if (retries >= 30) {
                log.warn("获取店铺缓存锁超时，shopId={}", id);
                return null;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("查询店铺缓存被中断", e);
            }
        }

        // 3. 抢到锁后必须释放：只有拿到锁的线程才进 finally，避免误删别人的锁
        try {
            // 3.1 双重检查：可能在等锁期间，别的线程已经把缓存建好了
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            if (shopJson != null) {
                return null;
            }

            // 3.2 查数据库
            shop = getById(id);

            // 3.3 不存在：缓存空值，解决缓存穿透
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            // 3.4 存在：写回缓存，TTL 加随机值防止缓存雪崩
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), randomTtl(), TimeUnit.MINUTES);
        } finally {
            // 4. 释放锁：Lua 校验 value，避免 TTL 到期后误删别人的锁
            unlock(lockKey, lockValue);
        }
        return shop;
    }

    /**
     * 方案三：逻辑过期解决缓存击穿
     * <p>
     * 缓存永不过期（不设 TTL），而是在 value 里存一个"逻辑过期时间"。
     * 读请求永远直接返回旧数据，只有发现逻辑过期时才抢锁、开异步线程重建缓存，
     * 因此读请求永远不会被阻塞，适合超高并发的热点 key。
     * <p>
     * 注意：这个方案要求缓存提前预热（saveShop2Redis），没预热过的 key 查不到数据。
     */
    private Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;

        // 1. 查缓存，未命中直接返回 null（逻辑过期方案不缓存空值，只服务热点数据）
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }

        // 2. 反序列化出数据和逻辑过期时间
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 3. 未过期：直接返回
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }

        // 4. 已过期：尝试抢锁，抢到就开异步线程重建缓存（当前请求仍返回旧数据）
        String lockKey = LOCK_SHOP_KEY + id;
        String lockValue = tryLock(lockKey);
        if (lockValue != null) {
            final String currentLockValue = lockValue;
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    log.error("重建店铺缓存失败，shopId={}", id, e);
                } finally {
                    unlock(lockKey, currentLockValue);
                }
            });
        }

        // 5. 没抢到锁也没关系，直接返回旧数据
        return shop;
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }

        // 1. 先更新数据库
        updateById(shop);

        // 2. 再删除缓存（保证下次查询能拿到最新数据）
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }

    @Override
    public void saveShop2Redis(Long id, Long expireSeconds) {
        // 1. 查数据库
        Shop shop = getById(id);
        if (shop == null) {
            return;
        }

        // 2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        // 3. 写入 Redis，注意这里不设置 TTL（逻辑过期由 expireTime 控制）
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1. 没有传经纬度，走普通的分页查询
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        // 2. 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = from + SystemConstants.DEFAULT_PAGE_SIZE;

        String key = SHOP_GEO_KEY + typeId;
        ensureGeoLoaded(key, typeId);

        // 3. 以当前坐标为中心，按距离升序查 5 公里内的店铺
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .radius(key,
                        new Circle(new Point(x, y), new Distance(5000)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs()
                                .includeDistance()
                                .limit(end)
                                .sortAscending());

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if (content.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        // 4. 截取当前页，并记录每个店铺的距离
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> pageList =
                content.subList(from, Math.min(end, content.size()));
        List<Long> ids = new ArrayList<>(pageList.size());
        Map<String, Distance> distanceMap = new HashMap<>();
        for (GeoResult<RedisGeoCommands.GeoLocation<String>> result : pageList) {
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            distanceMap.put(shopId, result.getDistance());
        }

        // 5. 按距离顺序查店铺（ORDER BY FIELD 保证顺序）
        List<Shop> shops = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + StrUtil.join(",", ids) + ")")
                .list();

        // 6. 把距离塞进 distance 字段，前端直接展示
        for (Shop shop : shops) {
            Distance distance = distanceMap.get(shop.getId().toString());
            if (distance != null) {
                shop.setDistance(distance.getValue());
            }
        }
        return Result.ok(shops);
    }

    /**
     * 第一次查询某个类型时，把店铺坐标加载到 Redis GEO（相当于 GEO 数据预热）
     */
    private void ensureGeoLoaded(String key, Integer typeId) {
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            return;
        }
        List<Shop> shops = query().eq("type_id", typeId).list();
        for (Shop shop : shops) {
            if (shop.getX() == null || shop.getY() == null) {
                continue;
            }
            stringRedisTemplate.opsForGeo().add(key,
                    new Point(shop.getX(), shop.getY()), shop.getId().toString());
        }
    }

    /**
     * 缓存 TTL：基础 30 分钟 + 0~9 分钟随机值，避免大量 key 同时过期造成缓存雪崩
     */
    private long randomTtl() {
        return CACHE_SHOP_TTL + RandomUtil.randomInt(0, 10);
    }

    /**
     * 获取互斥锁：SET key value NX EX seconds。
     * value 用 UUID 标识“锁的持有者”，释放时用 Lua 校验，防止误删别人的锁。
     *
     * @return 抢锁成功返回 UUID；失败返回 null
     */
    private String tryLock(String key) {
        String value = UUID.randomUUID().toString();
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, value, LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag) ? value : null;
    }

    /**
     * 释放锁：Lua 中比较 value，只有自己加的锁才删。
     */
    private void unlock(String key, String value) {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), value);
    }
}
