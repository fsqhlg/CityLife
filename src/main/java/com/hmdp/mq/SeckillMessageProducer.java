package com.hmdp.mq;

import com.hmdp.dto.SeckillMessage;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 秒杀消息生产者。
 * <p>
 * 只负责把 Redis outbox 里的消息投递到 RocketMQ，不直接建单。
 * 发送失败返回 false，由 outbox relay 保留消息、对账任务稍后重发。
 */
@Slf4j
@Component
public class SeckillMessageProducer {

    private static final long SEND_TIMEOUT_MS = 3_000L;

    @Resource
    private RocketMQTemplate rocketMQTemplate;

    @Resource
    private MeterRegistry meterRegistry;

    public boolean send(SeckillMessage message) {
        if (message == null || message.getOrderId() == null) {
            return false;
        }
        try {
            SendResult result = rocketMQTemplate.syncSend(
                    RocketMQConstants.SECKILL_ORDER_TOPIC, message, SEND_TIMEOUT_MS);
            if (result != null && SendStatus.SEND_OK.equals(result.getSendStatus())) {
                meterRegistry.counter("seckill_mq_send_total").increment();
                log.info("秒杀消息已投递 RocketMQ，orderId={}, msgId={}",
                        message.getOrderId(), result.getMsgId());
                return true;
            }
            meterRegistry.counter("seckill_mq_send_failed_total").increment();
            log.error("秒杀消息投递 RocketMQ 失败，orderId={}, result={}",
                    message.getOrderId(), result);
            return false;
        } catch (Exception e) {
            meterRegistry.counter("seckill_mq_send_failed_total").increment();
            // 超时/网络异常时消息可能已经到达 Broker，消费端靠 orderId 幂等
            log.error("秒杀消息投递 RocketMQ 异常，orderId={}", message.getOrderId(), e);
            return false;
        }
    }
}
