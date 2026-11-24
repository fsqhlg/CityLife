package com.hmdp.dto;

import lombok.Data;

/**
 * 秒杀结果查询返回体：前端轮询这个接口，区分“排队中 / 成功 / 失败”。
 */
@Data
public class OrderStatusDTO {

    private Long orderId;

    /** 数据库订单状态；没有落库时为 null */
    private Integer status;

    /** 状态描述：待支付、已支付、已取消等 */
    private String statusDesc;

    /**
     * 队列状态：
     * QUEUING 排队中；SUCCESS 已落库；FAILED 失败已回滚；NOT_FOUND 订单不存在
     */
    private String queueState;

    private String createTime;

    private String message;
}
