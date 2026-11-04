package com.example.kbagent.common.ratelimit;

import java.time.Duration;

/**
 * 令牌桶限流。桶容量为 {@code capacity}，令牌在 {@code period} 内匀速补满。
 */
public interface RateLimiter {

    Decision tryAcquire(String key, int capacity, Duration period);

    /** @param retryAfter 被拒绝时，距离下一个令牌可用的时间；放行时为零 */
    record Decision(boolean allowed, Duration retryAfter) {
    }
}
