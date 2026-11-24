package com.hmdp.mq;

import com.hmdp.dto.SeckillMessage;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * RocketMQ 秒杀订单消费者。
 * <p>
 * 消费成功才返回；异常直接抛出，让 RocketMQ 按 maxReconsumeTimes 重试。
 * 超过重试上限后，RocketMQ 会把消息放入 %DLQ%seckill-order-consumer。
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = RocketMQConstants.SECKILL_ORDER_TOPIC,
        consumerGroup = RocketMQConstants.SECKILL_ORDER_CONSUMER_GROUP,
        consumeThreadMax = 2,
        maxReconsumeTimes = 3
)
public class SeckillRocketMQConsumer implements RocketMQListener<SeckillMessage> {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public void onMessage(SeckillMessage message) {
        if (message == null || message.getOrderId() == null) {
            log.error("收到空的秒杀消息，直接拒绝");
            throw new IllegalArgumentException("空的秒杀消息");
        }
        try {
            voucherOrderService.handleSeckillMessage(message);
            log.info("RocketMQ 秒杀消息消费成功，orderId={}", message.getOrderId());
        } catch (Exception e) {
            // 抛出异常，RocketMQ 才会重试；不能吞掉异常
            log.error("RocketMQ 秒杀消息消费失败，orderId={}", message.getOrderId(), e);
            throw e;
        }
    }
}
