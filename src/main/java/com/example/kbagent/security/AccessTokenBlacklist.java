package com.example.kbagent.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 登出黑名单：登出时把 Access Token 的 jti 写入 Redis，直到该 Token 自然过期。
 */
@Component
public class AccessTokenBlacklist {

    private static final String KEY_PREFIX = "auth:bl:";
    /** 与 JwtTimestampValidator 默认允许的时钟偏差一致：过期后 60 秒内的 Token 仍会通过时间校验。 */
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public AccessTokenBlacklist(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public void revoke(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(clock.instant(), expiresAt).plus(CLOCK_SKEW);
        if (!ttl.isNegative() && !ttl.isZero()) {
            redis.opsForValue().set(KEY_PREFIX + jti, "1", ttl);
        }
    }

    public boolean isRevoked(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
    }
}
