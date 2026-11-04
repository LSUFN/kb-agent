package com.example.kbagent.common.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis 的令牌桶（§4.7）。读取、计算、写回在一个 Lua 脚本里完成，多实例并发时也是原子的；
 * 时间取 Redis 服务器的 TIME，不依赖各应用实例的时钟一致。
 */
@Component
public class RedisRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "rl:";

    /** 返回 {是否放行(1/0), 距离下一个令牌的毫秒数}。 */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TOKEN_BUCKET = RedisScript.of("""
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local ts = tonumber(state[2])
            if tokens == nil or ts == nil then
              tokens = capacity
              ts = now
            end
            tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate)
            local allowed = 0
            local retry = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              retry = math.ceil((1 - tokens) / rate)
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate) + 1000)
            return {allowed, retry}
            """, List.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Decision tryAcquire(String key, int capacity, Duration period) {
        if (capacity <= 0 || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("capacity 和 period 必须为正数");
        }
        double tokensPerMilli = capacity / (double) period.toMillis();
        List<?> result = redis.execute(TOKEN_BUCKET, List.of(KEY_PREFIX + key),
                String.valueOf(capacity), String.valueOf(tokensPerMilli));
        boolean allowed = ((Number) result.get(0)).longValue() == 1L;
        return new Decision(allowed, Duration.ofMillis(((Number) result.get(1)).longValue()));
    }
}
