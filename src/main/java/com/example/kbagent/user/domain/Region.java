package com.example.kbagent.user.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 业务数据的区域，取值与 biz_order.region、biz_anomaly_event.region 一致。
 */
public enum Region {

    EAST, NORTH, SOUTH, WEST, CENTRAL;

    /** 不可变副本，按枚举声明顺序迭代，保证接口输出稳定。 */
    public static Set<Region> immutableCopy(Collection<Region> regions) {
        if (regions == null || regions.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(regions));
    }
}
