package com.hmdp.task;

import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Set;

/**
 * M1：超时关单任务。
 * <p>
 * 下单时把 orderId 写进 ZSet，score = 应关闭时间；
 * 定时扫描到期的 orderId，条件更新订单状态（只有仍为待支付才关），再幂等回补库存。
 * 多实例部署时，条件 UPDATE 保证只有一个实例能关单成功。
 */
@Slf4j
@Component
public class OrderTimeoutTask {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private MeterRegistry meterRegistry;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void closeTimeoutOrders() {
        long now = System.currentTimeMillis();
        Set<String> orderIds = stringRedisTemplate.opsForZSet()
                .rangeByScore(RedisConstants.SECKILL_DELAY_KEY, 0, now, 0, 50);
        if (orderIds == null || orderIds.isEmpty()) {
            return;
        }

        for (String orderId : orderIds) {
            try {
                boolean closed = voucherOrderService.closeTimeoutOrder(Long.valueOf(orderId));
                if (closed) {
                    meterRegistry.counter("seckill_order_timeout_closed_total").increment();
                    log.info("超时关单成功，orderId={}", orderId);
                }
                // 已支付/已取消/订单不存在，都从延迟队列移除，避免无限扫描
                stringRedisTemplate.opsForZSet().remove(RedisConstants.SECKILL_DELAY_KEY, orderId);
            } catch (Exception e) {
                // 数据库异常时保留在 ZSet，下一轮继续尝试
                log.error("超时关单失败，orderId={}", orderId, e);
            }
        }
    }
}
