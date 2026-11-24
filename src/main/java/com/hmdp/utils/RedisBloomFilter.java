package com.hmdp.utils;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

import static com.hmdp.utils.RedisConstants.BLOOM_SHOP_KEY;

/**
 * 基于 Redis BitMap 的布隆过滤器（M6）。
 * <p>
 * 1M bit ≈ 128KB，3 个 MurmurHash seed，100 万以内店铺 id 的误判率足够低。
 * 布隆只能“判一定不存在”，不能“判一定存在”；命中的请求继续走缓存 + 空值兜底。
 */
@Component
public class RedisBloomFilter {

    private static final long BIT_SIZE = 1L << 20;
    private static final int HASH_COUNT = 3;
    private static final String READY_KEY = BLOOM_SHOP_KEY + ":ready";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public boolean isReady() {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(READY_KEY));
    }

    public void initIfAbsent(Collection<Long> ids) {
        if (isReady()) {
            return;
        }
        if (ids != null && !ids.isEmpty()) {
            stringRedisTemplate.executePipelined(new RedisCallback<Object>() {
                @Override
                public Object doInRedis(RedisConnection connection) throws DataAccessException {
                    byte[] key = BLOOM_SHOP_KEY.getBytes(StandardCharsets.UTF_8);
                    for (Long id : ids) {
                        for (int i = 0; i < HASH_COUNT; i++) {
                            long offset = offset(id, i);
                            connection.stringCommands().setBit(key, offset, true);
                        }
                    }
                    return null;
                }
            });
        }
        stringRedisTemplate.opsForValue().set(READY_KEY, "1");
    }

    public void add(Long id) {
        if (id == null) {
            return;
        }
        for (int i = 0; i < HASH_COUNT; i++) {
            stringRedisTemplate.opsForValue().setBit(BLOOM_SHOP_KEY, offset(id, i), true);
        }
    }

    public boolean mightContain(Long id) {
        if (id == null) {
            return false;
        }
        // key 被清掉/未初始化时 fail-open，宁可多查一次 DB，也不能把所有请求都判死
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(BLOOM_SHOP_KEY))) {
            return true;
        }
        for (int i = 0; i < HASH_COUNT; i++) {
            Boolean bit = stringRedisTemplate.opsForValue().getBit(BLOOM_SHOP_KEY, offset(id, i));
            if (!Boolean.TRUE.equals(bit)) {
                return false;
            }
        }
        return true;
    }

    private long offset(Long id, int index) {
        int hash = MurmurHash3.hash32(id.toString(), index);
        return Math.floorMod(hash, (int) BIT_SIZE);
    }
}
