package com.example.kbagent.user.web;

import static com.example.kbagent.support.TestActors.ANALYST;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import com.example.kbagent.security.AccessTokenBlacklist;
import com.example.kbagent.security.ActorCache;
import com.example.kbagent.security.JwtTokenService;
import com.example.kbagent.security.SecurityProperties;
import com.example.kbagent.support.SecuredWebMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 用真实签发的 JWT 走完整的认证链路：解码 → 签名/有效期/签发者/黑名单校验 → 按 sub 加载 Actor。
 */
@SecuredWebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    SecurityProperties properties;

    @Autowired
    ActorCache actorCache;

    @Autowired
    AccessTokenBlacklist blacklist;

    private String tokenFor(long userId, Clock clock) {
        return new JwtTokenService(jwtEncoder, properties, clock).issue(userId).value();
    }

    private String tokenFor(long userId) {
        return tokenFor(userId, Clock.systemUTC());
    }

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void validTokenReturnsActorLoadedOnServerSide() throws Exception {
        when(actorCache.get(2L)).thenReturn(Optional.of(ANALYST));

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + tokenFor(2L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(2))
                .andExpect(jsonPath("$.data.username").value("analyst"))
                .andExpect(jsonPath("$.data.role").value("ANALYST"))
                .andExpect(jsonPath("$.data.regionScope[0]").value("EAST"));
    }

    @Test
    void tokenOfDisabledOrDeletedUserIs401AccountDisabled() throws Exception {
        when(actorCache.get(3L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + tokenFor(3L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void loggedOutTokenIs401AndUserIsNotLoaded() throws Exception {
        when(blacklist.isRevoked(any())).thenReturn(true);

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + tokenFor(2L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verify(actorCache, never()).get(anyLong());
    }

    @Test
    void expiredTokenIs401() throws Exception {
        // 一小时前签发、有效期 15 分钟：已过期 45 分钟，超出 60 秒的时钟偏差容忍
        Clock anHourAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), Clock.systemUTC().getZone());

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + tokenFor(2L, anHourAgo)))
                .andExpect(status().isUnauthorized());
        verify(actorCache, never()).get(anyLong());
    }

    @Test
    void tokenSignedWithAnotherKeyIs401() throws Exception {
        JwtEncoder attacker = NimbusJwtEncoder.withSecretKey(new SecretKeySpec(
                        "another-secret-that-is-long-enough-0123456789".getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .algorithm(MacAlgorithm.HS256).build();
        String forged = new JwtTokenService(attacker, properties, Clock.systemUTC()).issue(1L).value();

        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
        verify(actorCache, never()).get(anyLong());
    }

    @Test
    void tokenWithWrongIssuerOrMissingJtiOrExpIs401() throws Exception {
        Instant now = Instant.now();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String wrongIssuer = jwtEncoder.encode(JwtEncoderParameters.from(header, JwtClaimsSet.builder()
                .issuer("someone-else").subject("1").id(UUID.randomUUID().toString())
                .issuedAt(now).expiresAt(now.plusSeconds(600)).build())).getTokenValue();
        String withoutJti = jwtEncoder.encode(JwtEncoderParameters.from(header, JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer()).subject("1")
                .issuedAt(now).expiresAt(now.plusSeconds(600)).build())).getTokenValue();
        // JwtTimestampValidator 会放行没有 exp 的 Token，由 ActiveTokenValidator 拒绝
        String withoutExp = jwtEncoder.encode(JwtEncoderParameters.from(header, JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer()).subject("1").id(UUID.randomUUID().toString())
                .issuedAt(now).build())).getTokenValue();

        for (String token : new String[] {wrongIssuer, withoutJti, withoutExp}) {
            mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        verify(actorCache, never()).get(anyLong());
    }

    @Test
    void malformedTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
