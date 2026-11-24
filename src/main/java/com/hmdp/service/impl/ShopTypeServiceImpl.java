package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 1. 先查 Redis List 缓存
        List<String> cacheList = stringRedisTemplate.opsForList()
                .range(CACHE_SHOP_TYPE_KEY, 0, -1);
        if (cacheList != null && !cacheList.isEmpty()) {
            List<ShopType> types = cacheList.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(types);
        }

        // 2. 缓存未命中，查数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if (typeList == null || typeList.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 3. 写回缓存（List 结构，保留顺序）
        List<String> jsonList = typeList.stream()
                .map(JSONUtil::toJsonStr)
                .collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY, jsonList);
        // 加 TTL：分类数据不常变，1 小时过期即可，避免缓存永久占内存
        stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY, 1, TimeUnit.HOURS);

        return Result.ok(typeList);
    }
}
