package com.example.kbagent.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 认证相关配置（app.security.*）。
 *
 * @param accessTokenTtl          Access Token 有效期（§3.3：15 分钟）
 * @param refreshTokenTtl         Refresh Token 有效期，每次刷新重新计时（§3.3：7 天）
 * @param actorCacheTtl           用户状态缓存的 TTL；管理员修改用户后会立即清除对应条目
 * @param loginRateLimitPerMinute 每个客户端 IP 每分钟允许的登录次数
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(
        @DefaultValue Jwt jwt,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("7d") Duration refreshTokenTtl,
        @DefaultValue("30s") Duration actorCacheTtl,
        @DefaultValue("10") int loginRateLimitPerMinute) {

    /** HS256 的最小密钥长度（RFC 7518 §3.2）。 */
    static final int MIN_SECRET_BYTES = 32;

    /**
     * @param secret 签名密钥，只能通过环境变量 JWT_SECRET 注入；缺失或不足 32 字节时拒绝启动
     */
    public record Jwt(String secret, @DefaultValue("kb-agent") String issuer) {

        public Jwt {
            if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
                throw new IllegalArgumentException(
                        "app.security.jwt.secret（环境变量 JWT_SECRET）未设置或不足 " + MIN_SECRET_BYTES + " 字节");
            }
        }

        public SecretKey secretKey() {
            return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        }

        @Override
        public String toString() {
            return "Jwt[secret=******, issuer=" + issuer + "]";
        }
    }
}
