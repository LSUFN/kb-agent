package com.example.kbagent.user.service;

import java.time.Instant;
import java.util.Set;

import com.example.kbagent.user.domain.Region;
import com.example.kbagent.user.domain.Role;
import com.example.kbagent.user.domain.SysUser;

/** 管理端看到的用户信息（不含口令哈希）。 */
public record UserView(long id, String username, String displayName, Role role, Set<Region> regionScope,
                       boolean enabled, Instant createdAt, Instant updatedAt) {

    public static UserView of(SysUser user) {
        return new UserView(user.id(), user.username(), user.displayName(), user.role(), user.regionScope(),
                user.enabled(), user.createdAt(), user.updatedAt());
    }
}
