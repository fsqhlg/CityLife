package com.hmdp.config;

import com.hmdp.common.BizException;
import com.hmdp.common.ErrorCode;
import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    /**
     * 业务异常：按错误码返回，不打印完整堆栈（避免日志被业务抖动刷屏）。
     */
    @ExceptionHandler(BizException.class)
    public Result handleBizException(BizException e) {
        log.warn("业务异常：code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    /**
     * @RequestBody 参数校验失败。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result handleValidException(MethodArgumentNotValidException e) {
        BindingResult bindingResult = e.getBindingResult();
        String message = "参数错误";
        FieldError fieldError = bindingResult.getFieldError();
        if (fieldError != null && fieldError.getDefaultMessage() != null) {
            message = fieldError.getDefaultMessage();
        }
        log.warn("参数校验失败：{}", message);
        return Result.fail(ErrorCode.PARAM_ERROR, message);
    }

    /**
     * @RequestParam / @PathVariable 参数校验失败。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result handleConstraintViolation(ConstraintViolationException e) {
        String message = "参数错误";
        if (e.getConstraintViolations() != null) {
            for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
                if (violation.getMessage() != null) {
                    message = violation.getMessage();
                    break;
                }
            }
        }
        log.warn("参数校验失败：{}", message);
        return Result.fail(ErrorCode.PARAM_ERROR, message);
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }
}
