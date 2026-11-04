package com.example.kbagent.common.api;

import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.common.trace.TraceContext;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应体：{code, message, data, traceId}。成功时 code 为 "OK"。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(String code, String message, T data, String traceId) {

    public static final String OK = "OK";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(OK, "success", data, TraceContext.currentTraceId());
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message, T details) {
        String msg = (message == null || message.isBlank()) ? errorCode.defaultMessage() : message;
        return new ApiResponse<>(errorCode.name(), msg, details, TraceContext.currentTraceId());
    }
}
