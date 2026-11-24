package com.hmdp.mq;

/**
 * RocketMQ 秒杀消息的固定命名。
 * <p>
 * 生产者和消费者共用同一组常量，避免配置写错后出现“消息发出去了但没人消费”。
 */
public final class RocketMQConstants {

    public static final String SECKILL_ORDER_TOPIC = "seckill-order-topic";
    public static final String SECKILL_ORDER_CONSUMER_GROUP = "seckill-order-consumer";

    private RocketMQConstants() {
    }
}
