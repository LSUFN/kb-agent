package com.example.kbagent.business.service;

import java.util.EnumSet;
import java.util.Set;

import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.domain.Region;

/**
 * 业务数据的区域范围（§4.4.3）。工具的服务端校验和业务 Service 共用这里的规则：
 * 范围只由服务端的 Actor 决定，模型给出的区域只能在这个范围内收窄。
 */
public final class DataScope {

    private DataScope() {
    }

    /** 收窄后为空：请求的区域不在用户范围内，或者用户没有任何业务数据权限。 */
    public static class ScopeDeniedException extends BusinessException {

        public ScopeDeniedException(String message) {
            super(ErrorCode.FORBIDDEN, message);
        }
    }

    /** 用户能查看的区域：ADMIN 全部，ANALYST 为分配给他的区域，USER 没有。 */
    public static Set<Region> regionsOf(Actor actor) {
        return switch (actor.role()) {
            case ADMIN -> Region.immutableCopy(EnumSet.allOf(Region.class));
            case ANALYST -> actor.regionScope();
            case USER -> Set.of();
        };
    }

    /**
     * 请求的区域与用户范围求交集：不指定区域时就是用户的全部范围。
     *
     * @throws ScopeDeniedException 交集为空
     */
    public static Set<Region> narrow(Actor actor, Region requested) {
        Set<Region> allowed = regionsOf(actor);
        if (allowed.isEmpty()) {
            throw new ScopeDeniedException("当前账号无权查看业务数据");
        }
        if (requested == null) {
            return allowed;
        }
        if (!allowed.contains(requested)) {
            throw new ScopeDeniedException("无权访问 " + requested + " 区域的数据");
        }
        return Set.of(requested);
    }
}
