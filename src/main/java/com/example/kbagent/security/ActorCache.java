package com.example.kbagent.security;

import java.util.Optional;

import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.domain.UserAccountChangedEvent;
import com.example.kbagent.user.service.UserAccountService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 每个请求都要按 JWT 的 sub 加载用户的最新状态（§3.3）。这里用短 TTL 的本地缓存减少数据库访问，
 * 同时在管理员修改用户后立即清除对应条目，所以停用账号、修改角色都立即生效。
 * <p>
 * 停用或不存在的用户缓存为 empty。MVP 是单实例部署，本地缓存足够；多实例时需要把清除广播到所有实例。
 */
@Component
public class ActorCache {

    private final Cache<Long, Optional<Actor>> cache;
    private final UserAccountService accounts;

    public ActorCache(UserAccountService accounts, SecurityProperties properties) {
        this.accounts = accounts;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.actorCacheTtl())
                .maximumSize(10_000)
                .build();
    }

    /** 账号存在且已启用时返回 Actor。 */
    public Optional<Actor> get(long userId) {
        return cache.get(userId, accounts::findActiveActor);
    }

    /**
     * 在事务提交之后再清除：如果在提交前清除，并发请求可能把尚未提交的旧状态重新读进缓存。
     * fallbackExecution 保证在没有事务的场景下也会执行。
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void onUserAccountChanged(UserAccountChangedEvent event) {
        cache.invalidate(event.userId());
    }
}
