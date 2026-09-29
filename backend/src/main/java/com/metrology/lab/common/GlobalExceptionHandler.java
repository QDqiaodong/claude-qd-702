package com.metrology.lab.common;

import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.persistence.OptimisticLockException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务校验失败：400 + 中文业务提示
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail(e.getMessage()));
    }

    /**
     * 参数注解校验失败：400 + 字段名与提示
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(message.isEmpty() ? "请求参数校验失败" : message));
    }

    /**
     * 请求体格式错误：400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail("请求体格式有误，请检查 JSON 字段类型"));
    }

    /**
     * 唯一约束冲突：409。挂接抢占（uk_device_active）属于并发正常业务结果——
     * 明确告知是谁被抢占，委托原数据未改动，可重新选择；其余唯一键冲突给通用提示。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleIntegrity(DataIntegrityViolationException e) {
        String text = String.valueOf(e.getMostSpecificCause().getMessage());
        if (text.contains("uk_device_active")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail("该标准器刚被另一笔委托抢先占用，本委托未被改动，请重新选择标准器后提交"));
        }
        if (text.contains("uk_order_device_active")) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.fail("挂接清单已被另一方更新，本委托未被改动，请刷新后重试"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.fail("存在唯一性约束冲突，请检查编号、手机号是否重复"));
    }

    /**
     * 乐观锁冲突：409。重复点击 / 两人同时推进同一委托时后提交者落到这里，
     * 只允许一笔成功，失败方刷新后按最新状态修正再提交。
     */
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ApiResponse<Void>> handleOptimistic(Exception e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail("委托刚被另一方更新（可能是重复提交），本次操作未生效，请刷新最新状态后重试"));
    }

    /** 行锁等待超时/死锁：409，提示按最新状态重试，不允许任何一笔静默丢操作 */
    @ExceptionHandler(org.springframework.dao.CannotAcquireLockException.class)
    public ResponseEntity<ApiResponse<Void>> handleLock(Exception e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail("当前有另一方正在操作该委托/标准器，请刷新最新状态后重试"));
    }

    /**
     * 兜底：500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail("服务异常：" + detail));
    }
}
