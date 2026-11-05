package com.example.kbagent.user.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 业务数据的区域，取值与 biz_order.region、biz_anomaly_event.region 一致。
 */
public enum Region {

    EAST("华东"), NORTH("华北"), SOUTH("华南"), WEST("西部"), CENTRAL("华中");

    private final String displayName;

    Region(String displayName) {
        this.displayName = displayName;
    }

    /** 中文名，用于提示词和报告。 */
    public String displayName() {
        return displayName;
    }

    /** 不可变副本，按枚举声明顺序迭代，保证接口输出稳定。 */
    public static Set<Region> immutableCopy(Collection<Region> regions) {
        if (regions == null || regions.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(regions));
    }
}
