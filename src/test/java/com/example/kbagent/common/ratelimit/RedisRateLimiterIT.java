package com.example.kbagent.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import com.example.kbagent.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 令牌桶 Lua 脚本在真实 Redis 上的行为。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RedisRateLimiterIT {

    @Autowired
    RateLimiter rateLimiter;

    @Autowired
    StringRedisTemplate redis;

    private static String newKey() {
        return "it:" + UUID.randomUUID();
    }

    @Test
    void allowsUpToCapacityThenRejectsWithRetryAfter() {
        String key = newKey();
        for (int i = 0; i < 3; i++) {
            assertThat(rateLimiter.tryAcquire(key, 3, Duration.ofMinutes(1)).allowed()).isTrue();
        }

        RateLimiter.Decision rejected = rateLimiter.tryAcquire(key, 3, Duration.ofMinutes(1));

        assertThat(rejected.allowed()).isFalse();
        // 每分钟 3 个令牌：最多等 20 秒就会补充一个
        assertThat(rejected.retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void bucketsAreIndependentPerKey() {
        String a = newKey();
        String b = newKey();
        assertThat(rateLimiter.tryAcquire(a, 1, Duration.ofMinutes(1)).allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire(a, 1, Duration.ofMinutes(1)).allowed()).isFalse();

        assertThat(rateLimiter.tryAcquire(b, 1, Duration.ofMinutes(1)).allowed()).isTrue();
    }

    @Test
    void tokensRefillOverTime() throws InterruptedException {
        String key = newKey();
        // 每秒 2 个令牌，即每 500 毫秒补充一个
        assertThat(rateLimiter.tryAcquire(key, 2, Duration.ofSeconds(1)).allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire(key, 2, Duration.ofSeconds(1)).allowed()).isTrue();
        assertThat(rateLimiter.tryAcquire(key, 2, Duration.ofSeconds(1)).allowed()).isFalse();

        Thread.sleep(600);

        assertThat(rateLimiter.tryAcquire(key, 2, Duration.ofSeconds(1)).allowed()).isTrue();
    }

    @Test
    void bucketKeyExpiresSoRedisDoesNotAccumulateKeys() {
        String key = newKey();
        rateLimiter.tryAcquire(key, 10, Duration.ofMinutes(1));

        Long ttlSeconds = redis.getExpire("rl:" + key);

        assertThat(ttlSeconds).isPositive().isLessThanOrEqualTo(61);
    }
}
