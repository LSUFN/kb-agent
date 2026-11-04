package com.example.kbagent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.common.error.RateLimitedException;
import com.example.kbagent.common.ratelimit.RateLimiter;
import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.domain.Region;
import com.example.kbagent.user.domain.Role;
import com.example.kbagent.user.service.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Actor ANALYST = new Actor(2L, "analyst", "华东分析员", Role.ANALYST, Set.of(Region.EAST));
    private static final RateLimiter.Decision ALLOWED = new RateLimiter.Decision(true, Duration.ZERO);

    @Mock
    UserAccountService accounts;
    @Mock
    JwtTokenService jwtTokens;
    @Mock
    RefreshTokenService refreshTokens;
    @Mock
    AccessTokenBlacklist blacklist;
    @Mock
    RateLimiter rateLimiter;

    private final SecurityProperties properties = new SecurityProperties(
            new SecurityProperties.Jwt("x".repeat(32), "kb-agent"),
            Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofSeconds(30), 10);

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(accounts, jwtTokens, refreshTokens, blacklist, rateLimiter, properties);
    }

    private static void assertErrorCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }

    private void allowLogin() {
        when(rateLimiter.tryAcquire("login:10.0.0.1", 10, Duration.ofMinutes(1))).thenReturn(ALLOWED);
    }

    @Test
    void loginIsRateLimitedPerClientIpBeforeCheckingPassword() {
        when(rateLimiter.tryAcquire("login:10.0.0.1", 10, Duration.ofMinutes(1)))
                .thenReturn(new RateLimiter.Decision(false, Duration.ofMillis(5100)));

        assertThatThrownBy(() -> service.login("analyst", "pw", "10.0.0.1"))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(6));
        verifyNoInteractions(accounts);
    }

    @Test
    void successfulLoginStartsANewRefreshTokenFamily() {
        allowLogin();
        JwtTokenService.AccessToken access = new JwtTokenService.AccessToken("at", "jti", Instant.now());
        when(accounts.checkCredentials("analyst", "pw")).thenReturn(new UserAccountService.Authenticated(ANALYST));
        when(refreshTokens.issue(2L)).thenReturn("rt");
        when(jwtTokens.issue(2L)).thenReturn(access);

        AuthService.IssuedTokens tokens = service.login("analyst", "pw", "10.0.0.1");

        assertThat(tokens).isEqualTo(new AuthService.IssuedTokens(access, Duration.ofMinutes(15), "rt",
                Duration.ofDays(7), ANALYST));
    }

    @Test
    void badCredentialsAndDisabledAccountsGetNoTokens() {
        allowLogin();
        when(accounts.checkCredentials("analyst", "bad")).thenReturn(new UserAccountService.BadCredentials());
        when(accounts.checkCredentials("analyst", "pw")).thenReturn(new UserAccountService.Disabled());

        assertErrorCode(() -> service.login("analyst", "bad", "10.0.0.1"), ErrorCode.BAD_CREDENTIALS);
        assertErrorCode(() -> service.login("analyst", "pw", "10.0.0.1"), ErrorCode.ACCOUNT_DISABLED);
        verifyNoInteractions(refreshTokens, jwtTokens);
    }

    @Test
    void refreshRotatesWithinTheSameFamily() {
        when(refreshTokens.consume("rt1")).thenReturn(new RefreshTokenService.Valid(2L, "fam"));
        when(accounts.findActiveActor(2L)).thenReturn(Optional.of(ANALYST));
        when(refreshTokens.issueInFamily(2L, "fam")).thenReturn("rt2");
        when(jwtTokens.issue(2L)).thenReturn(new JwtTokenService.AccessToken("at", "jti", Instant.now()));

        assertThat(service.refresh("rt1").refreshToken()).isEqualTo("rt2");
    }

    @Test
    void refreshForDisabledAccountRevokesTheFamily() {
        when(refreshTokens.consume("rt1")).thenReturn(new RefreshTokenService.Valid(2L, "fam"));
        when(accounts.findActiveActor(2L)).thenReturn(Optional.empty());

        assertErrorCode(() -> service.refresh("rt1"), ErrorCode.ACCOUNT_DISABLED);
        verify(refreshTokens).revokeFamily("fam");
        verifyNoInteractions(jwtTokens);
    }

    @Test
    void reusedRefreshTokenRevokesTheWholeFamily() {
        when(refreshTokens.consume("rt1")).thenReturn(new RefreshTokenService.Reused(2L, "fam"));

        assertErrorCode(() -> service.refresh("rt1"), ErrorCode.REFRESH_TOKEN_INVALID);
        verify(refreshTokens).revokeFamily("fam");
        verify(refreshTokens, never()).issueInFamily(anyLong(), anyString());
        verifyNoInteractions(jwtTokens, accounts);
    }

    @Test
    void unknownRefreshTokenIsRejected() {
        when(refreshTokens.consume("nope")).thenReturn(new RefreshTokenService.Invalid());

        assertErrorCode(() -> service.refresh("nope"), ErrorCode.REFRESH_TOKEN_INVALID);
        verifyNoInteractions(jwtTokens, accounts);
    }

    @Test
    void logoutBlacklistsAccessTokenUntilExpiryAndRevokesRefreshToken() {
        Instant expiresAt = Instant.now().plusSeconds(600);
        Jwt jwt = Jwt.withTokenValue("at").header("alg", "HS256").subject("2").claim("jti", "jti-1")
                .issuedAt(Instant.now()).expiresAt(expiresAt).build();

        service.logout(new ActorAuthentication(ANALYST, jwt), "rt");

        verify(blacklist).revoke("jti-1", expiresAt);
        verify(refreshTokens).revoke("rt", 2L);
    }

    @Test
    void logoutWithoutRefreshTokenOnlyBlacklistsAccessToken() {
        Jwt jwt = Jwt.withTokenValue("at").header("alg", "HS256").subject("2").claim("jti", "jti-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();

        service.logout(new ActorAuthentication(ANALYST, jwt), null);

        verifyNoInteractions(refreshTokens);
    }
}
