package com.example.kbagent.user.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.common.error.FieldViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class SysUserTest {

    private static SysUser analyst(Region... regions) {
        return SysUser.create("ana", "hash", "Ana", Role.ANALYST, Set.of(regions));
    }

    private static void assertRegionScopeViolation(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat((List<?>) e.details()).singleElement()
                            .extracting(v -> ((FieldViolation) v).field()).isEqualTo("regionScope");
                });
    }

    @Test
    void analystRequiresAtLeastOneRegion() {
        assertRegionScopeViolation(() -> SysUser.create("ana", "hash", "Ana", Role.ANALYST, Set.of()));
        assertRegionScopeViolation(() -> SysUser.create("ana", "hash", "Ana", Role.ANALYST, null));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"USER", "ADMIN"})
    void onlyAnalystMayHaveRegions(Role role) {
        assertRegionScopeViolation(() -> SysUser.create("u", "hash", "U", role, Set.of(Region.EAST)));
        assertThat(SysUser.create("u", "hash", "U", role, Set.of()).regionScope()).isEmpty();
    }

    @Test
    void regionScopeIsReturnedInDeclarationOrder() {
        assertThat(analyst(Region.WEST, Region.EAST, Region.NORTH).regionScope())
                .containsExactly(Region.EAST, Region.NORTH, Region.WEST);
    }

    @Test
    void demotingAnalystWithoutGivingScopeClearsRegions() {
        SysUser user = analyst(Region.EAST);

        user.changeRoleAndScope(Role.USER, null);

        assertThat(user.role()).isEqualTo(Role.USER);
        assertThat(user.regionScope()).isEmpty();
    }

    @Test
    void promotingToAnalystRequiresRegions() {
        SysUser user = SysUser.create("u", "hash", "U", Role.USER, Set.of());

        assertRegionScopeViolation(() -> user.changeRoleAndScope(Role.ANALYST, null));

        user.changeRoleAndScope(Role.ANALYST, Set.of(Region.NORTH));
        assertThat(user.role()).isEqualTo(Role.ANALYST);
        assertThat(user.regionScope()).containsExactly(Region.NORTH);
    }

    @Test
    void changingOnlyScopeKeepsRole() {
        SysUser user = analyst(Region.EAST);

        user.changeRoleAndScope(null, Set.of(Region.SOUTH, Region.CENTRAL));

        assertThat(user.role()).isEqualTo(Role.ANALYST);
        assertThat(user.regionScope()).containsExactly(Region.SOUTH, Region.CENTRAL);
    }

    @Test
    void rejectedChangeLeavesUserUntouched() {
        SysUser user = analyst(Region.EAST);

        assertRegionScopeViolation(() -> user.changeRoleAndScope(Role.USER, Set.of(Region.EAST)));

        assertThat(user.role()).isEqualTo(Role.ANALYST);
        assertThat(user.regionScope()).containsExactly(Region.EAST);
    }

    @Test
    void toActorCarriesIdentityRoleAndScope() {
        SysUser user = analyst(Region.EAST);
        ReflectionTestUtils.setField(user, "id", 7L);

        Actor actor = user.toActor();

        assertThat(actor).isEqualTo(new Actor(7L, "ana", "Ana", Role.ANALYST, Set.of(Region.EAST)));
        assertThat(actor.isAdmin()).isFalse();
    }
}
