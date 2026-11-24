package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;

/**
 * 滑动窗口限流（M5）。脚本见 rate_limit_sliding.lua。
 */
@Component
public class RedisRateLimiter {

    private static final DefaultRedisScript<Long> SLIDING_SCRIPT;

    static {
        SLIDING_SCRIPT = new DefaultRedisScript<>();
        SLIDING_SCRIPT.setLocation(new ClassPathResource("rate_limit_sliding.lua"));
        SLIDING_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public boolean tryAcquire(String key, int limit, long windowMs) {
        if (limit <= 0 || windowMs <= 0) {
            return true;
        }
        Long result = stringRedisTemplate.execute(
                SLIDING_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(windowMs),
                String.valueOf(limit),
                UUID.randomUUID().toString());
        return result != null && result == 1L;
    }
}
