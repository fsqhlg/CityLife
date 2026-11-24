package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.common.BizException;
import com.hmdp.common.ErrorCode;
import com.hmdp.common.OrderStatus;
import com.hmdp.dto.OrderStatusDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillMessage;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SeckillLedger;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

import static com.hmdp.utils.RedisConstants.SECKILL_DELAY_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_LEDGER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_OUTBOX_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_RETURNED_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 秒杀订单实现（M1-M4）。
 * <p>
 * 关键设计：
 * 1. 请求线程只做 Lua 原子扣减 + 写 Redis outbox，不碰数据库；
 * 2. SeckillOutboxRelay 把 outbox 消息转发 RocketMQ，RocketMQ 消费者异步建单；
 * 3. Redis 扣减流水 + 定时对账，兜住“扣了库存但没落库”的半成品；
 * 4. 订单状态机 + ZSet 延迟队列，支付/取消/超时都走同一条库存回补逻辑。
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 自己注入自己（拿到 Spring 代理对象），保证 createVoucherOrder 的 @Transactional 生效。
     */
    @Resource
    private IVoucherOrderService proxy;

    @Resource
    private SeckillLedger seckillLedger;

    @Resource
    private MeterRegistry meterRegistry;

    @Value("${seckill.pay-timeout-minutes:15}")
    private long payTimeoutMinutes;

    /**
     * 秒杀 Lua：库存校验 + 一人一单 + 扣减 + 流水 + XADD，六件事原子完成。
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    /**
     * 库存回补 Lua：SADD 幂等标记，只有第一次取消/超时才真正 INCR。
     */
    private static final DefaultRedisScript<Long> RETURN_STOCK_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        RETURN_STOCK_SCRIPT = new DefaultRedisScript<>();
        RETURN_STOCK_SCRIPT.setLocation(new ClassPathResource("return_stock.lua"));
        RETURN_STOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail(ErrorCode.UNAUTHORIZED);
        }
        Long userId = user.getId();

        // 1. 全局唯一订单 id：时间戳 + Redis 自增
        long orderId = redisIdWorker.nextId("order");

        meterRegistry.counter("seckill_requests_total").increment();

        // 2. Lua 原子执行：库存/一人一单/扣减/流水/XADD
        String stockKey = SECKILL_STOCK_KEY + voucherId;
        String orderKey = SECKILL_ORDER_KEY + voucherId;
        String ledgerKey = SECKILL_LEDGER_KEY + voucherId;

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(stockKey, orderKey, SECKILL_OUTBOX_KEY, ledgerKey),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId),
                String.valueOf(System.currentTimeMillis()));

        int r = result == null ? -1 : result.intValue();
        if (r == 1) {
            meterRegistry.counter("seckill_stock_empty_total").increment();
            return Result.fail(ErrorCode.STOCK_EMPTY);
        }
        if (r == 2) {
            meterRegistry.counter("seckill_duplicate_total").increment();
            return Result.fail(ErrorCode.DUPLICATE_ORDER);
        }
        if (r == 3 || r == 4) {
            log.error("秒杀脚本执行条件不满足，voucherId={}, result={}", voucherId, r);
            return Result.fail(ErrorCode.SECKILL_NOT_READY);
        }
        if (r != 0) {
            log.error("秒杀脚本执行失败，voucherId={}, result={}", voucherId, r);
            return Result.fail(ErrorCode.SYSTEM_ERROR);
        }

        meterRegistry.counter("seckill_accepted_total").increment();
        // 立即返回 orderId，前端轮询 /result/{orderId} 看排队/成功/失败
        return Result.ok(orderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean createVoucherOrder(VoucherOrder voucherOrder) {
        if (voucherOrder == null || voucherOrder.getId() == null) {
            return false;
        }

        // 1. 幂等：同一个 orderId 已经落库，直接当成功
        if (getById(voucherOrder.getId()) != null) {
            return true;
        }

        // 2. 兜底：同一用户同一券已有订单（包含已取消，规则上不允许复购）
        VoucherOrder existing = query()
                .eq("user_id", voucherOrder.getUserId())
                .eq("voucher_id", voucherOrder.getVoucherId())
                .one();
        if (existing != null) {
            if (existing.getId().equals(voucherOrder.getId())) {
                return true;
            }
            // 已有另一笔订单：不能再建新单，否则会出现“Redis 成功、DB 没订单”的假成功
            log.warn("用户已有该券订单，拒绝重复建单，userId={}, voucherId={}, existingOrderId={}, newOrderId={}",
                    voucherOrder.getUserId(), voucherOrder.getVoucherId(), existing.getId(), voucherOrder.getId());
            return false;
        }

        // 3. 数据库库存条件扣减，防止 Redis 与 DB 不一致时超卖
        boolean stockUpdated = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!stockUpdated) {
            log.error("数据库库存不足，voucherId={}, orderId={}", voucherOrder.getVoucherId(), voucherOrder.getId());
            return false;
        }

        // 4. 落库：状态 1 待支付；唯一索引 uk_user_voucher 作为最后一道幂等防线
        voucherOrder.setStatus(OrderStatus.UNPAID.getCode())
                .setCreateTime(LocalDateTime.now())
                .setPayType(1);
        save(voucherOrder);

        // 5. 写入延迟队列：到期还没支付，由 OrderTimeoutTask 关单并回补库存
        long deadline = System.currentTimeMillis() + payTimeoutMinutes * 60_000L;
        stringRedisTemplate.opsForZSet().add(SECKILL_DELAY_KEY, voucherOrder.getId().toString(), deadline);
        return true;
    }

    @Override
    public void handleSeckillMessage(SeckillMessage message) {
        if (message == null) {
            return;
        }

        // MQ 可能重复投递：如果这笔扣减已经回滚/取消，不能再建单，否则会出现“库存已加回但订单又创建”的反向不一致
        SeckillLedger.Entry ledger = seckillLedger.get(message.getVoucherId(), message.getOrderId());
        if (ledger != null && (SeckillLedger.ROLLED_BACK.equals(ledger.getStatus())
                || SeckillLedger.CANCELED.equals(ledger.getStatus()))) {
            log.warn("秒杀消息对应流水已终结，跳过建单，orderId={}, status={}",
                    message.getOrderId(), ledger.getStatus());
            return;
        }

        VoucherOrder order = new VoucherOrder()
                .setId(message.getOrderId())
                .setUserId(message.getUserId())
                .setVoucherId(message.getVoucherId());

        try {
            boolean created = proxy.createVoucherOrder(order);
            if (created) {
                seckillLedger.markDone(message.getVoucherId(), message.getOrderId());
            } else {
                // 数据库库存不够：立即回滚 Redis，避免用户白抢
                rollbackSeckillOrder(message.getOrderId(), message.getUserId(), message.getVoucherId());
            }
        } catch (DuplicateKeyException e) {
            // 唯一索引兜底：说明订单已经存在，按成功处理
            seckillLedger.markDone(message.getVoucherId(), message.getOrderId());
            log.warn("重复订单被唯一索引拦截，按幂等成功处理，orderId={}", message.getOrderId());
        }
    }

    @Override
    public Result payOrder(Long orderId) {
        UserDTO user = requireLogin();
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!user.getId().equals(order.getUserId())) {
            return Result.fail(ErrorCode.FORBIDDEN);
        }
        if (Integer.valueOf(OrderStatus.PAID.getCode()).equals(order.getStatus())) {
            return Result.ok("订单已支付");
        }
        if (!Integer.valueOf(OrderStatus.UNPAID.getCode()).equals(order.getStatus())) {
            return Result.fail(ErrorCode.PARAM_ERROR, "当前订单状态不允许支付");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean updated = update()
                .eq("id", orderId)
                .eq("user_id", user.getId())
                .eq("status", OrderStatus.UNPAID.getCode())
                .set("status", OrderStatus.PAID.getCode())
                .set("pay_time", now)
                .set("update_time", now)
                .update();
        if (!updated) {
            return Result.fail(ErrorCode.PARAM_ERROR, "支付失败，订单状态已变更");
        }
        // 已支付，从延迟队列移除，不再触发超时关单
        stringRedisTemplate.opsForZSet().remove(SECKILL_DELAY_KEY, orderId.toString());
        return Result.ok();
    }

    @Override
    public Result cancelOrder(Long orderId) {
        UserDTO user = requireLogin();
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!user.getId().equals(order.getUserId())) {
            return Result.fail(ErrorCode.FORBIDDEN);
        }
        if (Integer.valueOf(OrderStatus.CANCELED.getCode()).equals(order.getStatus())) {
            return Result.ok("订单已取消");
        }
        if (!Integer.valueOf(OrderStatus.UNPAID.getCode()).equals(order.getStatus())) {
            return Result.fail(ErrorCode.PARAM_ERROR, "已支付/已核销的订单不能直接取消");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean updated = update()
                .eq("id", orderId)
                .eq("user_id", user.getId())
                .eq("status", OrderStatus.UNPAID.getCode())
                .set("status", OrderStatus.CANCELED.getCode())
                .set("close_type", 1)
                .set("update_time", now)
                .update();
        if (!updated) {
            return Result.fail(ErrorCode.PARAM_ERROR, "取消失败，订单状态已变更");
        }
        stringRedisTemplate.opsForZSet().remove(SECKILL_DELAY_KEY, orderId.toString());
        returnStock(order, true);
        return Result.ok();
    }

    @Override
    public boolean closeTimeoutOrder(Long orderId) {
        VoucherOrder order = getById(orderId);
        if (order == null || !Integer.valueOf(OrderStatus.UNPAID.getCode()).equals(order.getStatus())) {
            return false;
        }
        LocalDateTime deadline = order.getCreateTime() == null
                ? LocalDateTime.now().minusMinutes(payTimeoutMinutes)
                : order.getCreateTime().plusMinutes(payTimeoutMinutes);
        if (deadline.isAfter(LocalDateTime.now())) {
            return false;
        }

        boolean updated = update()
                .eq("id", orderId)
                .eq("status", OrderStatus.UNPAID.getCode())
                .set("status", OrderStatus.CANCELED.getCode())
                .set("close_type", 2)
                .set("update_time", LocalDateTime.now())
                .update();
        if (!updated) {
            return false;
        }
        returnStock(order, true);
        stringRedisTemplate.opsForZSet().remove(SECKILL_DELAY_KEY, orderId.toString());
        return true;
    }

    @Override
    public Result queryOrderResult(Long orderId) {
        UserDTO user = requireLogin();
        VoucherOrder order = getById(orderId);
        if (order != null) {
            if (!user.getId().equals(order.getUserId())) {
                return Result.fail(ErrorCode.FORBIDDEN);
            }
            OrderStatusDTO dto = new OrderStatusDTO();
            dto.setOrderId(orderId);
            dto.setStatus(order.getStatus());
            dto.setStatusDesc(OrderStatus.descOf(order.getStatus()));
            dto.setQueueState("SUCCESS");
            dto.setCreateTime(order.getCreateTime() == null ? null : order.getCreateTime().format(TIME_FORMATTER));
            dto.setMessage("订单已创建");
            return Result.ok(dto);
        }

        // 还没落库：看扣减流水
        SeckillLedger.Entry entry = findLedgerByOrderId(orderId);
        OrderStatusDTO dto = new OrderStatusDTO();
        dto.setOrderId(orderId);
        if (entry == null) {
            dto.setQueueState("NOT_FOUND");
            dto.setMessage("订单不存在或已过期");
            return Result.ok(dto);
        }
        if (SeckillLedger.PENDING.equals(entry.getStatus())) {
            dto.setQueueState("QUEUING");
            dto.setStatusDesc("排队中");
            dto.setMessage("订单正在异步落库，请稍后查询");
        } else if (SeckillLedger.FAILED.equals(entry.getStatus())
                || SeckillLedger.ROLLED_BACK.equals(entry.getStatus())) {
            dto.setQueueState("FAILED");
            dto.setStatusDesc("抢购失败");
            dto.setMessage("抢购失败，库存已回滚");
        } else if (SeckillLedger.DONE.equals(entry.getStatus())) {
            dto.setQueueState("QUEUING");
            dto.setStatusDesc("已受理");
            dto.setMessage("订单已受理，等待落库");
        } else if (SeckillLedger.CANCELED.equals(entry.getStatus())) {
            dto.setQueueState("FAILED");
            dto.setStatusDesc("已取消");
            dto.setMessage("订单已取消，库存已回补");
        }
        dto.setCreateTime(entry.getCreateTime() <= 0 ? null
                : LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(entry.getCreateTime()),
                java.time.ZoneId.systemDefault()).format(TIME_FORMATTER));
        return Result.ok(dto);
    }

    @Override
    public Result queryMyOrders(Integer status, Integer current) {
        UserDTO user = requireLogin();
        int pageNo = current == null || current < 1 ? 1 : current;
        Page<VoucherOrder> page = query()
                .eq("user_id", user.getId())
                .eq(status != null, "status", status)
                .orderByDesc("create_time")
                .page(new Page<>(pageNo, SystemConstants.DEFAULT_PAGE_SIZE));
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @Override
    public boolean rollbackSeckillOrder(Long orderId, Long userId, Long voucherId) {
        String stockKey = SECKILL_STOCK_KEY + voucherId;
        String orderKey = SECKILL_ORDER_KEY + voucherId;
        Long result = stringRedisTemplate.execute(
                RETURN_STOCK_SCRIPT,
                Arrays.asList(stockKey, orderKey, SECKILL_RETURNED_KEY),
                orderId.toString(),
                userId.toString(),
                "0");
        seckillLedger.markRolledBack(voucherId, orderId);
        log.warn("回滚秒杀订单，orderId={}, userId={}, voucherId={}, result={}", orderId, userId, voucherId, result);
        return true;
    }

    private boolean returnStock(VoucherOrder order, boolean canceled) {
        if (order == null || order.getUserId() == null || order.getVoucherId() == null) {
            return false;
        }
        String stockKey = SECKILL_STOCK_KEY + order.getVoucherId();
        String orderKey = SECKILL_ORDER_KEY + order.getVoucherId();
        Long result = stringRedisTemplate.execute(
                RETURN_STOCK_SCRIPT,
                Arrays.asList(stockKey, orderKey, SECKILL_RETURNED_KEY),
                order.getId().toString(),
                order.getUserId().toString(),
                "1");
        boolean returned = result != null && result == 1L;
        if (returned) {
            // 数据库库存同步 +1：否则 Redis 库存和 DB 库存会越差越多
            boolean dbUpdated = seckillVoucherService.update()
                    .setSql("stock = stock + 1")
                    .eq("voucher_id", order.getVoucherId())
                    .update();
            if (!dbUpdated) {
                log.error("数据库库存回补失败，orderId={}, voucherId={}", order.getId(), order.getVoucherId());
            }
        }
        if (canceled) {
            seckillLedger.markCanceled(order.getVoucherId(), order.getId());
        }
        if (returned) {
            log.info("库存回补成功，orderId={}, voucherId={}", order.getId(), order.getVoucherId());
        }
        return returned;
    }

    private SeckillLedger.Entry findLedgerByOrderId(Long orderId) {
        for (Long voucherId : seckillLedger.voucherIds()) {
            SeckillLedger.Entry entry = seckillLedger.get(voucherId, orderId);
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    private UserDTO requireLogin() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }
}
