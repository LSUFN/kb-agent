package com.example.kbagent.security;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * 签发 Access Token。
 * <p>
 * Token 只携带身份（sub = 用户 ID）和用于登出的 jti，不携带角色：角色和区域范围每次请求都从数据库加载，
 * 修改后立即生效，不必等 Token 过期。
 */
@Component
public class JwtTokenService {

    /** 必须显式指定 HS256：NimbusJwtEncoder 在不传 header 时默认使用 RS256，与对称密钥不匹配。 */
    private static final JwsHeader HEADER = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();

    public record AccessToken(String value, String jti, Instant expiresAt) {
    }

    private final JwtEncoder encoder;
    private final SecurityProperties properties;
    private final Clock clock;

    public JwtTokenService(JwtEncoder encoder, SecurityProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public AccessToken issue(long userId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        String jti = UUID.randomUUID().toString();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .subject(Long.toString(userId))
                .id(jti)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        String value = encoder.encode(JwtEncoderParameters.from(HEADER, claims)).getTokenValue();
        return new AccessToken(value, jti, expiresAt);
    }
}
