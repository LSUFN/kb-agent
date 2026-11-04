package com.example.kbagent.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import java.time.Instant;
import java.util.Set;

import com.example.kbagent.security.ActorAuthentication;
import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.domain.Region;
import com.example.kbagent.user.domain.Role;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** 切片测试中直接以某个 Actor 身份发起请求（跳过 JWT 解码）。 */
public final class TestActors {

    public static final Actor ADMIN = new Actor(1L, "admin", "系统管理员", Role.ADMIN, Set.of());
    public static final Actor ANALYST = new Actor(2L, "analyst", "华东分析员", Role.ANALYST, Set.of(Region.EAST));
    public static final Actor USER = new Actor(3L, "user", "普通员工", Role.USER, Set.of());

    private TestActors() {
    }

    public static RequestPostProcessor as(Actor actor) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject(Long.toString(actor.userId()))
                .claim("jti", "jti-" + actor.userId())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build();
        return authentication(new ActorAuthentication(actor, jwt));
    }
}
