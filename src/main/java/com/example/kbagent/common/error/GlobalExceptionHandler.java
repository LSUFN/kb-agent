package com.example.kbagent.common.error;

import java.util.List;

import com.example.kbagent.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：所有异常都转换为统一响应体。
 * 原则：客户端只看到错误码、通用描述和 traceId；异常堆栈和内部信息只写服务端日志。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.errorCode();
        if (code.httpStatus().is5xxServerError()) {
            log.warn("business error code={} message={}", code, ex.getMessage());
        } else {
            log.debug("business error code={} message={}", code, ex.getMessage());
        }
        return build(code, ex.getMessage(), ex.details());
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ApiResponse<Object>> handleRateLimited(RateLimitedException ex) {
        return ResponseEntity.status(ex.errorCode().httpStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .body(ApiResponse.error(ex.errorCode(), ex.getMessage(), null));
    }

    /**
     * 方法级鉴权（@PreAuthorize）失败时，异常从 Controller 抛出，不经过安全过滤链的 AccessDeniedHandler；
     * 如果不在这里显式处理，会被下面的 Exception 兜底变成 500。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Object>> handleAccessDenied(AccessDeniedException ex) {
        return build(ErrorCode.FORBIDDEN, null, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Object>> handleAuthentication(AuthenticationException ex) {
        return build(ErrorCode.UNAUTHORIZED, null, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleBodyValidation(MethodArgumentNotValidException ex) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldViolation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, null, violations);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Object>> handleParamValidation(HandlerMethodValidationException ex) {
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(err -> new FieldViolation(
                                r.getMethodParameter().getParameterName(), err.getDefaultMessage())))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, null, violations);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class,
            MultipartException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Object>> handleBadRequest(Exception ex) {
        log.debug("bad request: {}", ex.getMessage());
        return build(ErrorCode.BAD_REQUEST, null, null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Object>> handleTooLarge(MaxUploadSizeExceededException ex) {
        return build(ErrorCode.PAYLOAD_TOO_LARGE, null, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNoResource(NoResourceFoundException ex) {
        return build(ErrorCode.NOT_FOUND, null, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, null, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception ex) {
        log.error("unexpected error", ex);
        return build(ErrorCode.INTERNAL_ERROR, null, null);
    }

    private static ResponseEntity<ApiResponse<Object>> build(ErrorCode code, String message, Object details) {
        return ResponseEntity.status(code.httpStatus()).body(ApiResponse.error(code, message, details));
    }
}
