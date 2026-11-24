package com.hmdp.task;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mq.SeckillMessageProducer;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SeckillLedger;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * M3：秒杀对账任务。
 * <p>
 * 扫描扣减流水里 PENDING / FAILED 的记录：
 * 1. 订单已经落库 -> 把流水改成 DONE；
 * 2. 订单没落库 -> 重新投递 RocketMQ（不绕过 MQ 直接写 DB）；
 * 3. 重投超过上限 -> 回滚 Redis 库存，流水标记 ROLLED_BACK；
 * 4. 同时检查库存是否出现负数或超过初始值，做告警埋点。
 */
@Slf4j
@Component
public class SeckillReconcileTask {

    private static final long RECONCILE_DELAY_MS = 90_000L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private SeckillLedger seckillLedger;

    @Resource
    private SeckillMessageProducer seckillMessageProducer;

    @Resource
    private MeterRegistry meterRegistry;

    @Value("${seckill.max-retry:3}")
    private int maxRetry;

    @Scheduled(fixedDelayString = "${seckill.reconcile-interval-ms:60000}", initialDelay = 60_000)
    public void reconcile() {
        for (Long voucherId : seckillLedger.voucherIds()) {
            checkStock(voucherId);
            List<SeckillLedger.Entry> unfinished = seckillLedger.listUnfinished(voucherId);
            for (SeckillLedger.Entry entry : unfinished) {
                // 刚扣减不久的流水先不动，避免和正常消费者抢同一条消息
                if (System.currentTimeMillis() - entry.getCreateTime() < RECONCILE_DELAY_MS) {
                    continue;
                }
                meterRegistry.counter("seckill_reconcile_unfinished_total").increment();
                handleEntry(entry);
            }
        }
    }

    private void handleEntry(SeckillLedger.Entry entry) {
        try {
            // 1. 已经落库：只需要修正流水状态
            VoucherOrder order = voucherOrderService.getById(entry.getOrderId());
            if (order != null) {
                seckillLedger.markDone(entry.getVoucherId(), entry.getOrderId());
                return;
            }

            // 2. 没落库：重新投递 RocketMQ，由消费者建单；不绕过 MQ 直接写 DB
            meterRegistry.counter("seckill_reconcile_republish_total").increment();
            boolean sent = seckillMessageProducer.send(seckillLedger.toMessage(entry));
            if (!sent) {
                throw new IllegalStateException("对账重投 RocketMQ 失败");
            }
        } catch (Exception e) {
            int retry = seckillLedger.bumpRetry(entry.getVoucherId(), entry.getOrderId());
            log.error("对账重投失败，orderId={}, retry={}", entry.getOrderId(), retry, e);
            if (retry >= maxRetry) {
                voucherOrderService.rollbackSeckillOrder(
                        entry.getOrderId(), entry.getUserId(), entry.getVoucherId());
                meterRegistry.counter("seckill_reconcile_rollback_total").increment();
                log.error("对账超过重试上限，已回滚 Redis 库存，orderId={}", entry.getOrderId());
            }
        }
    }

    private void checkStock(Long voucherId) {
        String current = stringRedisTemplate.opsForValue()
                .get(RedisConstants.SECKILL_STOCK_KEY + voucherId);
        String initial = stringRedisTemplate.opsForValue()
                .get(RedisConstants.SECKILL_STOCK_INIT_KEY + voucherId);
        if (current == null || initial == null) {
            return;
        }
        long currentValue = Long.parseLong(current);
        long initialValue = Long.parseLong(initial);
        if (currentValue < 0 || currentValue > initialValue) {
            meterRegistry.counter("seckill_stock_anomaly_total").increment();
            log.error("秒杀库存异常，voucherId={}, current={}, initial={}", voucherId, currentValue, initialValue);
        }

        // Redis 库存和数据库库存对不上，说明有订单扣减/回补只完成了一半，先告警
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher != null && voucher.getStock() != null && voucher.getStock().longValue() != currentValue) {
            meterRegistry.counter("seckill_stock_mismatch_total").increment();
            log.error("秒杀库存与数据库不一致，voucherId={}, redis={}, db={}",
                    voucherId, currentValue, voucher.getStock());
        }
    }
}
