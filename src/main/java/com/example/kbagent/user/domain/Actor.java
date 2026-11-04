package com.example.kbagent.user.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.Set;

/**
 * 当前请求的操作者。
 * <p>
 * 每个请求都由服务端根据 JWT 的 {@code sub} 从数据库重新加载（短 TTL 缓存），角色和区域范围以数据库为准，
 * 不信任客户端提供的任何身份信息。业务 Service 的方法签名显式接收 Actor，而不是从 ThreadLocal 读取：
 * 流式模式下工具在 Reactor 线程上执行，读不到 SecurityContext（ADR-07，Spike S4/S5）。
 */
public record Actor(long userId, String username, String displayName, Role role, Set<Region> regionScope)
        implements Serializable {

    public Actor {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(role, "role");
        regionScope = Region.immutableCopy(regionScope);
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
