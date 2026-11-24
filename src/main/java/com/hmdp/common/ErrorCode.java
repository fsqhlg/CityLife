package com.hmdp.common;

/**
 * 统一错误码。
 * <p>
 * 业务异常统一抛 {@link BizException}，由 WebExceptionAdvice 转成 Result，
 * 避免各 Controller 里散落 "if (...) return Result.fail("...")"。
 */
public enum ErrorCode {

    SUCCESS(200, "成功"),
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "没有操作权限"),
    NOT_FOUND(404, "资源不存在"),
    RATE_LIMIT(429, "请求过于频繁，请稍后再试"),

    STOCK_EMPTY(5001, "库存不足"),
    DUPLICATE_ORDER(5002, "不能重复下单"),
    ORDER_NOT_FOUND(5003, "订单不存在"),
    SECKILL_NOT_READY(5004, "秒杀活动不存在或尚未开始"),
    SECKILL_PROCESSING(5005, "订单排队处理中"),

    SYSTEM_ERROR(500, "系统异常，请稍后重试");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
