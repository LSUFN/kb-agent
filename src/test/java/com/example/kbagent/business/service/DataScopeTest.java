package com.example.kbagent.business.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;

import com.example.kbagent.business.service.DataScope.ScopeDeniedException;
import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.domain.Region;
import com.example.kbagent.user.domain.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** §4.4.3 第 4 步：请求的区域与用户范围求交集。 */
class DataScopeTest {

    private static final Actor ADMIN = new Actor(1L, "admin", "管理员", Role.ADMIN, Set.of());
    private static final Actor EAST_SOUTH = new Actor(2L, "a", "分析员", Role.ANALYST,
            EnumSet.of(Region.EAST, Region.SOUTH));
    private static final Actor USER = new Actor(3L, "u", "员工", Role.USER, Set.of());

    @Test
    void withoutARequestedRegionTheWholeScopeIsUsed() {
        assertThat(DataScope.narrow(ADMIN, null)).containsExactly(Region.values());
        assertThat(DataScope.narrow(EAST_SOUTH, null)).containsExactly(Region.EAST, Region.SOUTH);
    }

    @ParameterizedTest
    @EnumSource(Region.class)
    void adminCanNarrowToAnyRegion(Region region) {
        assertThat(DataScope.narrow(ADMIN, region)).containsExactly(region);
    }

    @ParameterizedTest
    @EnumSource(Region.class)
    void analystCanOnlyNarrowWithinHisRegions(Region region) {
        if (EAST_SOUTH.regionScope().contains(region)) {
            assertThat(DataScope.narrow(EAST_SOUTH, region)).containsExactly(region);
        } else {
            assertThatThrownBy(() -> DataScope.narrow(EAST_SOUTH, region))
                    .isInstanceOf(ScopeDeniedException.class)
                    .hasMessage("无权访问 " + region + " 区域的数据");
        }
    }

    @Test
    void userHasNoBusinessData() {
        assertThat(DataScope.regionsOf(USER)).isEmpty();
        assertThatThrownBy(() -> DataScope.narrow(USER, null)).isInstanceOf(ScopeDeniedException.class);
        assertThatThrownBy(() -> DataScope.narrow(USER, Region.EAST)).isInstanceOf(ScopeDeniedException.class);
    }

    @Test
    void analystWithoutRegionsHasNoBusinessData() {
        Actor unassigned = new Actor(4L, "b", "未分配", Role.ANALYST, Set.of());
        assertThatThrownBy(() -> DataScope.narrow(unassigned, null)).isInstanceOf(ScopeDeniedException.class);
    }
}
