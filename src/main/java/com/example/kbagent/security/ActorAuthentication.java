package com.example.kbagent.security;

import java.util.List;

import com.example.kbagent.user.domain.Actor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 认证成功后放入 SecurityContext 的对象：principal 是从数据库加载的 {@link Actor}，
 * 原始 JWT 保留下来供登出使用（需要 jti 和过期时间）。
 * Controller 通过 {@code @AuthenticationPrincipal Actor} 取得当前用户。
 */
public final class ActorAuthentication extends AbstractAuthenticationToken {

    private final Actor actor;
    private final Jwt jwt;

    public ActorAuthentication(Actor actor, Jwt jwt) {
        super(List.of(new SimpleGrantedAuthority(actor.role().authority())));
        this.actor = actor;
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public Actor getPrincipal() {
        return actor;
    }

    /** 认证已经完成，不再对外暴露凭据。 */
    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return actor.username();
    }

    public Jwt jwt() {
        return jwt;
    }
}
