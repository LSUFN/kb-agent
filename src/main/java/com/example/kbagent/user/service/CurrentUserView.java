package com.example.kbagent.user.service;

import java.util.Set;

import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.domain.Region;
import com.example.kbagent.user.domain.Role;

/**
 * 当前登录用户的信息（/users/me、登录响应）。前端据此控制菜单显示，真正的权限由后端判断。
 */
public record CurrentUserView(long id, String username, String displayName, Role role, Set<Region> regionScope) {

    public static CurrentUserView of(Actor actor) {
        return new CurrentUserView(actor.userId(), actor.username(), actor.displayName(), actor.role(),
                actor.regionScope());
    }
}
