package com.hmdp;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisData;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 预热「逻辑过期」缓存，并验证 JSON 能正确序列化/反序列化。
     * <p>
     * 想手动体验逻辑过期：
     * 1. 把最后一行删除缓存的代码注释掉；
     * 2. 运行本测试；
     * 3. 把 ShopServiceImpl#queryById 里的 queryWithMutex 换成 queryWithLogicalExpire；
     * 4. 启动项目访问 /shop/1。
     * <p>
     * 注意：逻辑过期格式（{data, expireTime}）和互斥锁格式（纯 Shop JSON）共用同一个 key，
     * 两种方案不要混用，否则解析出来的对象字段是空的。
     */
    @Test
    void testSaveShop2Redis() {
        Long shopId = 1L;

        shopService.saveShop2Redis(shopId, 10L);

        String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + shopId);
        assertNotNull(json, "缓存写入失败");

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);

        assertNotNull(redisData.getExpireTime(), "逻辑过期时间解析失败");
        assertEquals(shopId, shop.getId());
        assertNotNull(shop.getName(), "店铺名称解析失败");

        // 用完删掉，避免污染互斥锁方案使用的普通缓存格式
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
    }

    /**
     * UV 统计演示：用 HyperLogLog 记录 10 万个用户，误差在 1% 以内。
     * <p>
     * PFADD 不会真的存 10 万个元素，最多只占 12 KB。
     */
    @Test
    void testHyperLogLog() {
        String key = "uv:blog";
        String[] values = new String[1000];
        int j = 0;

        for (int i = 0; i < 100_000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if (j == 999) {
                stringRedisTemplate.opsForHyperLogLog().add(key, values);
            }
        }

        Long uv = stringRedisTemplate.opsForHyperLogLog().size(key);
        System.out.println("HyperLogLog 统计 UV = " + uv + "（真实值 100000）");

        // 用完清理，避免污染 Redis
        stringRedisTemplate.delete(key);
    }
}
