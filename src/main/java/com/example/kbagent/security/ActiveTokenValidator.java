package com.example.kbagent.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 在签名、有效期、签发者校验之外，要求 Token 带 exp 和 jti，且未被登出。
 * <p>
 * JwtTimestampValidator 在缺少 exp 时会放行，所以这里显式要求；登出时计算黑名单 TTL 也依赖 exp。
 */
public class ActiveTokenValidator implements OAuth2TokenValidator<Jwt> {

    private final AccessTokenBlacklist blacklist;

    public ActiveTokenValidator(AccessTokenBlacklist blacklist) {
        this.blacklist = blacklist;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (jwt.getExpiresAt() == null) {
            return failure("The token has no exp claim");
        }
        String jti = jwt.getId();
        if (jti == null || jti.isBlank()) {
            return failure("The token has no jti claim");
        }
        if (blacklist.isRevoked(jti)) {
            return failure("The token has been revoked");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }
}
