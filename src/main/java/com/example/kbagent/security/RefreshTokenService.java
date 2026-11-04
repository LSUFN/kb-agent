package com.example.kbagent.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Refresh Token：不透明随机串，保存在 Redis 中，每次刷新都轮换，并检测重用（§4.8）。
 * <p>
 * Redis 中只保存令牌的 SHA-256，不保存原文。键：
 * <ul>
 *   <li>{@code auth:rt:{hash}} → {@code userId:familyId}，当前有效的令牌</li>
 *   <li>{@code auth:rt:used:{hash}} → {@code userId:familyId}，已经轮换掉的旧令牌，保留到它原本的过期时间</li>
 *   <li>{@code auth:rt:family:{familyId}} → 该令牌族当前有效令牌的 hash，用于整族撤销</li>
 * </ul>
 * 一次登录产生一个令牌族。已轮换的旧令牌再次出现，说明它可能被窃取，此时撤销整个令牌族，
 * 攻击者和合法用户都需要重新登录。前端必须串行刷新（同一时刻只发一个刷新请求），否则会被误判为重用。
 */
@Component
public class RefreshTokenService {

    private static final String TOKEN_KEY = "auth:rt:";
    private static final String USED_KEY = "auth:rt:used:";
    private static final String FAMILY_KEY = "auth:rt:family:";

    /**
     * 原子地“消费”一个令牌：存在则删除并留下已使用标记；不存在时检查是否是已使用过的令牌。
     * 返回 {状态, userId:familyId}。
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CONSUME = RedisScript.of("""
            local v = redis.call('GET', KEYS[1])
            if v then
              local ttl = redis.call('PTTL', KEYS[1])
              redis.call('DEL', KEYS[1])
              if ttl > 0 then
                redis.call('SET', KEYS[2], v, 'PX', ttl)
              end
              return {'VALID', v}
            end
            local used = redis.call('GET', KEYS[2])
            if used then
              return {'REUSED', used}
            end
            return {'INVALID', ''}
            """, List.class);

    public sealed interface Consumption permits Valid, Reused, Invalid {
    }

    /** 令牌有效，已被消费，调用方应在同一令牌族内签发新令牌。 */
    public record Valid(long userId, String familyId) implements Consumption {
    }

    /** 已经轮换掉的旧令牌被再次使用。 */
    public record Reused(long userId, String familyId) implements Consumption {
    }

    /** 不存在、已过期或已撤销。 */
    public record Invalid() implements Consumption {
    }

    private final StringRedisTemplate redis;
    private final SecurityProperties properties;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(StringRedisTemplate redis, SecurityProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 登录：开启一个新的令牌族。 */
    public String issue(long userId) {
        return issueInFamily(userId, UUID.randomUUID().toString());
    }

    /** 刷新：在原令牌族内签发下一个令牌，有效期重新计时。 */
    public String issueInFamily(long userId, String familyId) {
        String raw = newRawToken();
        String hash = hash(raw);
        redis.opsForValue().set(TOKEN_KEY + hash, userId + ":" + familyId, properties.refreshTokenTtl());
        redis.opsForValue().set(FAMILY_KEY + familyId, hash, properties.refreshTokenTtl());
        return raw;
    }

    public Consumption consume(String rawToken) {
        String hash = hash(rawToken);
        List<?> result = redis.execute(CONSUME, List.of(TOKEN_KEY + hash, USED_KEY + hash));
        return switch ((String) result.get(0)) {
            case "VALID" -> parse((String) result.get(1), Valid::new);
            case "REUSED" -> parse((String) result.get(1), Reused::new);
            default -> new Invalid();
        };
    }

    public void revokeFamily(String familyId) {
        String currentHash = redis.opsForValue().getAndDelete(FAMILY_KEY + familyId);
        if (currentHash != null) {
            redis.delete(TOKEN_KEY + currentHash);
        }
    }

    /** 登出时撤销令牌所在的整个令牌族；令牌不属于 userId 时忽略。 */
    public void revoke(String rawToken, long userId) {
        String value = redis.opsForValue().get(TOKEN_KEY + hash(rawToken));
        if (value == null) {
            return;
        }
        Valid owner = parse(value, Valid::new);
        if (owner.userId() == userId) {
            revokeFamily(owner.familyId());
        }
    }

    private String newRawToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static <T> T parse(String value, BiFunction<Long, String, T> factory) {
        int sep = value.indexOf(':');
        return factory.apply(Long.parseLong(value.substring(0, sep)), value.substring(sep + 1));
    }

    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
