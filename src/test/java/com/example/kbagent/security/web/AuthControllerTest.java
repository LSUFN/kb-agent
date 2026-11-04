package com.example.kbagent.security.web;

import static com.example.kbagent.support.TestActors.ANALYST;
import static com.example.kbagent.support.TestActors.as;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.common.error.RateLimitedException;
import com.example.kbagent.security.ActorAuthentication;
import com.example.kbagent.security.AuthService;
import com.example.kbagent.security.JwtTokenService;
import com.example.kbagent.support.SecuredWebMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SecuredWebMvcTest(AuthController.class)
class AuthControllerTest {

    private static final String LOGIN_BODY = "{\"username\":\"analyst\",\"password\":\"Passw0rd!\"}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AuthService authService;

    private static AuthService.IssuedTokens issued() {
        return new AuthService.IssuedTokens(
                new JwtTokenService.AccessToken("access-token", "jti-1", Instant.now().plusSeconds(900)),
                Duration.ofMinutes(15), "refresh-token", Duration.ofDays(7), ANALYST);
    }

    @Test
    void loginIsPublicAndReturnsTokensWithCurrentUser() throws Exception {
        when(authService.login(eq("analyst"), eq("Passw0rd!"), anyString())).thenReturn(issued());

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.data.refreshExpiresIn").value(604800))
                .andExpect(jsonPath("$.data.user.role").value("ANALYST"))
                .andExpect(jsonPath("$.data.user.regionScope[0]").value("EAST"));
    }

    @Test
    void loginBodyIsValidated() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(authService);
    }

    @Test
    void badCredentialsIs401() throws Exception {
        when(authService.login(any(), any(), any())).thenThrow(new BusinessException(ErrorCode.BAD_CREDENTIALS));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void rateLimitedLoginIs429WithRetryAfter() throws Exception {
        when(authService.login(any(), any(), any())).thenThrow(new RateLimitedException(Duration.ofMillis(4200)));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void invalidRefreshTokenIs401() throws Exception {
        when(authService.refresh("stale")).thenThrow(new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));

        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"stale\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void logoutRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(authService);
    }

    @Test
    void logoutRevokesAccessTokenAndGivenRefreshToken() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").with(as(ANALYST))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"rt-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        verify(authService).logout(any(ActorAuthentication.class), eq("rt-1"));
    }

    @Test
    void logoutWithoutBodyOnlyRevokesAccessToken() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").with(as(ANALYST)))
                .andExpect(status().isOk());
        verify(authService).logout(any(ActorAuthentication.class), isNull());
    }
}
