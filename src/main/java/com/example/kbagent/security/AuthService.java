package com.example.kbagent.security;

import java.time.Duration;
import java.util.Optional;

import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.common.error.RateLimitedException;
import com.example.kbagent.common.ratelimit.RateLimiter;
import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.service.UserAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * 登录、刷新、登出（§4.8）。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 登录或刷新成功后签发的令牌。 */
    public record IssuedTokens(JwtTokenService.AccessToken accessToken, Duration accessTokenTtl,
                               String refreshToken, Duration refreshTokenTtl, Actor actor) {
    }

    private final UserAccountService accounts;
    private final JwtTokenService jwtTokens;
    private final RefreshTokenService refreshTokens;
    private final AccessTokenBlacklist blacklist;
    private final RateLimiter rateLimiter;
    private final SecurityProperties properties;

    public AuthService(UserAccountService accounts, JwtTokenService jwtTokens, RefreshTokenService refreshTokens,
                       AccessTokenBlacklist blacklist, RateLimiter rateLimiter, SecurityProperties properties) {
        this.accounts = accounts;
        this.jwtTokens = jwtTokens;
        this.refreshTokens = refreshTokens;
        this.blacklist = blacklist;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /** 按客户端 IP 限流，防止暴力破解口令。 */
    public IssuedTokens login(String username, String password, String clientIp) {
        RateLimiter.Decision decision = rateLimiter.tryAcquire(
                "login:" + clientIp, properties.loginRateLimitPerMinute(), Duration.ofMinutes(1));
        if (!decision.allowed()) {
            throw new RateLimitedException(decision.retryAfter());
        }
        return switch (accounts.checkCredentials(username, password)) {
            case UserAccountService.Authenticated(Actor actor) -> issue(actor, refreshTokens.issue(actor.userId()));
            case UserAccountService.BadCredentials b -> throw new BusinessException(ErrorCode.BAD_CREDENTIALS);
            case UserAccountService.Disabled d -> throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        };
    }

    /** 轮换 Refresh Token：旧令牌立即失效；旧令牌被重复使用时撤销整个令牌族。 */
    public IssuedTokens refresh(String rawRefreshToken) {
        return switch (refreshTokens.consume(rawRefreshToken)) {
            case RefreshTokenService.Valid(long userId, String familyId) -> {
                // 直接查库而不是走缓存：刷新频率低，必须拿到最新的账号状态
                Optional<Actor> actor = accounts.findActiveActor(userId);
                if (actor.isEmpty()) {
                    refreshTokens.revokeFamily(familyId);
                    throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
                }
                yield issue(actor.get(), refreshTokens.issueInFamily(userId, familyId));
            }
            case RefreshTokenService.Reused(long userId, String familyId) -> {
                log.warn("refresh token reuse detected, revoking token family userId={} familyId={}", userId, familyId);
                refreshTokens.revokeFamily(familyId);
                throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
            }
            case RefreshTokenService.Invalid i -> throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        };
    }

    /** 当前 Access Token 进入黑名单；如果带了 Refresh Token，撤销它所在的令牌族。 */
    public void logout(ActorAuthentication authentication, String rawRefreshToken) {
        Jwt jwt = authentication.jwt();
        blacklist.revoke(jwt.getId(), jwt.getExpiresAt());
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokens.revoke(rawRefreshToken, authentication.getPrincipal().userId());
        }
    }

    private IssuedTokens issue(Actor actor, String refreshToken) {
        return new IssuedTokens(jwtTokens.issue(actor.userId()), properties.accessTokenTtl(),
                refreshToken, properties.refreshTokenTtl(), actor);
    }
}
