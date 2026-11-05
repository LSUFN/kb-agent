package com.example.kbagent.common.error;

import org.springframework.http.HttpStatus;

/**
 * 错误码与 HTTP 状态码的对应关系。新增业务错误码时在此登记，保证前后端语义一致。
 */
public enum ErrorCode {

    BAD_REQUEST(HttpStatus.BAD_REQUEST, "请求参数错误"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "参数校验失败"),
    INVALID_FILE(HttpStatus.BAD_REQUEST, "只支持 PDF 文件"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "未登录或登录已过期"),
    BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED, "账号已停用"),
    /** Refresh Token 无效、过期、已被使用或已撤销。前端收到后应跳转登录页。 */
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "没有权限执行此操作"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),
    CONFLICT(HttpStatus.CONFLICT, "资源状态冲突"),
    USERNAME_TAKEN(HttpStatus.CONFLICT, "用户名已存在"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "上传内容过大"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试"),
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "模型服务异常，请稍后重试"),
    UPSTREAM_TIMEOUT(HttpStatus.BAD_GATEWAY, "模型服务超时，请稍后重试"),
    /** 模型反复调用工具（达到尝试上限），本轮被终止（§4.4.2）。 */
    AGENT_ABORTED(HttpStatus.BAD_GATEWAY, "模型反复调用工具，已终止本轮回答，请换个问法再试"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
