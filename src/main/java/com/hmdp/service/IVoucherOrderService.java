package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillMessage;
import com.hmdp.entity.VoucherOrder;

/**
 * 秒杀订单服务。
 * <p>
 * 请求链路：Lua 原子扣减 + XADD -> 直接返回 orderId -> Stream 消费者异步落库。
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 秒杀优惠券下单：Lua 里完成库存校验、一人一单、扣减、写流水、入 Stream。
     *
     * @param voucherId 优惠券 id
     * @return 成功时 data 为订单 id（前端拿到后轮询 /voucher-order/result/{orderId}）
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 真正创建订单（扣数据库库存 + 写订单表），由消费者或对账任务调用。
     * 返回 false 表示库存不足等业务失败；重复订单视为已成功。
     */
    boolean createVoucherOrder(VoucherOrder voucherOrder);

    /**
     * 消费一条 Stream 消息：建单 + 更新扣减流水状态。
     */
    void handleSeckillMessage(SeckillMessage message);

    /** 模拟支付 */
    Result payOrder(Long orderId);

    /** 用户主动取消 */
    Result cancelOrder(Long orderId);

    /** 超时关单（由 OrderTimeoutTask 调用） */
    boolean closeTimeoutOrder(Long orderId);

    /** 查询秒杀结果：排队中 / 成功 / 失败 */
    Result queryOrderResult(Long orderId);

    /** 我的订单分页 */
    Result queryMyOrders(Integer status, Integer current);

    /** 对账失败后的回滚：删已购标记 + 回补库存 + 更新流水 */
    boolean rollbackSeckillOrder(Long orderId, Long userId, Long voucherId);
}
