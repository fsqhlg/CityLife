package com.hmdp.controller;

import com.hmdp.annotation.RateLimit;
import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 秒杀下单 + 订单状态机接口。
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 秒杀入口：按用户 5 次/秒限流，防止脚本连点/刷接口。
     */
    @PostMapping("seckill/{id}")
    @RateLimit(prefix = "seckill:user", limit = 5, windowMs = 1000)
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 模拟支付：把待支付订单改成已支付，并从延迟队列移除。
     */
    @PostMapping("pay/{orderId}")
    public Result pay(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.payOrder(orderId);
    }

    /**
     * 用户主动取消：回补库存、从延迟队列移除。
     */
    @PostMapping("cancel/{orderId}")
    public Result cancel(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.cancelOrder(orderId);
    }

    /**
     * 秒杀结果查询：前端拿到 orderId 后轮询，区分排队中/成功/失败。
     */
    @GetMapping("result/{orderId}")
    public Result result(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.queryOrderResult(orderId);
    }

    /**
     * 我的订单：支持按状态筛选、分页。
     */
    @GetMapping("my")
    public Result myOrders(@RequestParam(value = "status", required = false) Integer status,
                           @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return voucherOrderService.queryMyOrders(status, current);
    }
}
