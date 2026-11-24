package com.hmdp.dto;

import com.hmdp.common.ErrorCode;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class Result {

    private Boolean success;

    /**
     * 统一错误码：200 成功；400 参数错误；401 未登录；429 限流；5001 库存不足等。
     * 前端可以不看这个字段，但排查问题和接监控时很有用。
     */
    private Integer code;

    private String errorMsg;

    private Object data;

    private Long total;

    private Result(Boolean success, Integer code, String errorMsg, Object data, Long total) {
        this.success = success;
        this.code = code;
        this.errorMsg = errorMsg;
        this.data = data;
        this.total = total;
    }

    public static Result ok() {
        return new Result(true, ErrorCode.SUCCESS.getCode(), null, null, null);
    }

    public static Result ok(Object data) {
        return new Result(true, ErrorCode.SUCCESS.getCode(), null, data, null);
    }

    public static Result ok(List<?> data, Long total) {
        return new Result(true, ErrorCode.SUCCESS.getCode(), null, data, total);
    }

    public static Result fail(String errorMsg) {
        return new Result(false, ErrorCode.SYSTEM_ERROR.getCode(), errorMsg, null, null);
    }

    public static Result fail(ErrorCode errorCode) {
        return new Result(false, errorCode.getCode(), errorCode.getMessage(), null, null);
    }

    public static Result fail(ErrorCode errorCode, String errorMsg) {
        return new Result(false, errorCode.getCode(), errorMsg, null, null);
    }
}
