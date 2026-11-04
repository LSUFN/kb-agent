package com.example.kbagent.security;

import com.example.kbagent.user.domain.Actor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * 把通过校验的 JWT 转换为 {@link ActorAuthentication}：身份取自 sub，角色和区域范围取自数据库（经短 TTL 缓存）。
 * 账号已停用或已不存在时抛出 {@link DisabledException}，由认证入口返回 401 ACCOUNT_DISABLED。
 */
@Component
public class ActorAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final ActorCache actorCache;

    public ActorAuthenticationConverter(ActorCache actorCache) {
        this.actorCache = actorCache;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new InvalidBearerTokenException("Invalid subject");
        }
        Actor actor = actorCache.get(userId)
                .orElseThrow(() -> new DisabledException("Account disabled or deleted"));
        return new ActorAuthentication(actor, jwt);
    }
}
