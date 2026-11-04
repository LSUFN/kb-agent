package com.example.kbagent.common.error;

import java.time.Duration;

/**
 * 触发限流。全局异常处理会返回 429，并通过 Retry-After 头告诉客户端多少秒后可以重试。
 */
public class RateLimitedException extends BusinessException {

    private final transient Duration retryAfter;

    public RateLimitedException(Duration retryAfter) {
        super(ErrorCode.RATE_LIMITED);
        this.retryAfter = retryAfter;
    }

    /** 向上取整到秒，至少 1 秒。 */
    public long retryAfterSeconds() {
        long millis = retryAfter == null ? 0 : retryAfter.toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }
}
