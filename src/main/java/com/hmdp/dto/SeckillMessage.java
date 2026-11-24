package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 秒杀消息体：Lua 扣减成功后写入 Redis outbox，再由 relay 转发到 RocketMQ，
 * 消费者按这个结构建单。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillMessage {

    private Long orderId;
    private Long userId;
    private Long voucherId;

    public static SeckillMessage from(Map<String, String> map) {
        if (map == null) {
            return null;
        }
        String orderId = map.get("orderId");
        String userId = map.get("userId");
        String voucherId = map.get("voucherId");
        if (orderId == null || userId == null || voucherId == null) {
            return null;
        }
        return new SeckillMessage(Long.valueOf(orderId), Long.valueOf(userId), Long.valueOf(voucherId));
    }
}
