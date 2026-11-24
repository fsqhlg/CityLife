package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 全局唯一 ID 生成器
 * <p>
 * 结构：1 位符号位 + 31 位时间戳（秒） + 32 位 Redis 自增序列号
 * <pre>
 * 0 | 0000000000000000000000000000000 | 00000000000000000000000000000000
 *   |            31 bit 时间戳          |          32 bit 序列号
 * </pre>
 * 时间戳以 2022-01-01 00:00:00 为起点，可以用 69 年；序列号按天、按业务自增，单日上限 2^32。
 */
@Component
public class RedisIdWorker {

    /**
     * 起始时间戳（秒）：2022-01-01 00:00:00 UTC
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    /**
     * 序列号位数
     */
    private static final int COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成全局唯一 ID
     *
     * @param keyPrefix 业务前缀，例如 order
     * @return 唯一 ID
     */
    public long nextId(String keyPrefix) {
        // 1. 当前时间戳（秒） - 起始时间戳，用 Instant 保证是真正的 epoch 秒
        long timestamp = Instant.now().getEpochSecond() - BEGIN_TIMESTAMP;

        // 2. 按天生成 Redis 自增序列号，key 形如 icr:order:2026:10:29
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3. 拼接：时间戳左移 32 位，再按位或上序列号
        return timestamp << COUNT_BITS | count;
    }
}
