package com.example.kbagent.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.domain.Region;
import com.example.kbagent.user.domain.Role;
import com.example.kbagent.user.domain.SysUser;
import com.example.kbagent.user.repository.SysUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserAccountServiceTest {

    private static final String PASSWORD = "Passw0rd!";

    @Mock
    SysUserRepository users;

    /** 低强度 BCrypt，让测试跑得快；spy 用来确认“用户不存在时也做了哈希比对”。 */
    private final PasswordEncoder encoder = spy(new BCryptPasswordEncoder(4));

    private UserAccountService service;
    private String passwordHash;

    @BeforeEach
    void setUp() {
        service = new UserAccountService(users, encoder);
        // 预先算好：不能在 when(...).thenReturn(...) 的参数里调用 spy，否则 Mockito 判定为桩设置未完成
        passwordHash = encoder.encode(PASSWORD);
    }

    private SysUser storedUser(boolean enabled) {
        SysUser user = SysUser.create("analyst", passwordHash, "华东分析员", Role.ANALYST, Set.of(Region.EAST));
        ReflectionTestUtils.setField(user, "id", 2L);
        user.setEnabled(enabled);
        return user;
    }

    @Test
    void correctPasswordReturnsActorLoadedFromDatabase() {
        when(users.findByUsername("analyst")).thenReturn(Optional.of(storedUser(true)));

        assertThat(service.checkCredentials("analyst", PASSWORD)).isEqualTo(new UserAccountService.Authenticated(
                new Actor(2L, "analyst", "华东分析员", Role.ANALYST, Set.of(Region.EAST))));
    }

    @Test
    void usernameIsTrimmedAndCaseInsensitive() {
        when(users.findByUsername("analyst")).thenReturn(Optional.of(storedUser(true)));

        assertThat(service.checkCredentials("  Analyst ", PASSWORD))
                .isInstanceOf(UserAccountService.Authenticated.class);
    }

    @Test
    void unknownUserIsBadCredentialsButStillComparesAHash() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThat(service.checkCredentials("ghost", PASSWORD)).isInstanceOf(UserAccountService.BadCredentials.class);
        verify(encoder).matches(eq(PASSWORD), anyString());
    }

    @Test
    void wrongPasswordIsBadCredentials() {
        when(users.findByUsername("analyst")).thenReturn(Optional.of(storedUser(true)));

        assertThat(service.checkCredentials("analyst", "wrong-password"))
                .isInstanceOf(UserAccountService.BadCredentials.class);
    }

    @Test
    void disabledAccountIsReportedOnlyWhenPasswordIsCorrect() {
        when(users.findByUsername("analyst")).thenReturn(Optional.of(storedUser(false)));

        assertThat(service.checkCredentials("analyst", PASSWORD)).isInstanceOf(UserAccountService.Disabled.class);
        assertThat(service.checkCredentials("analyst", "wrong-password"))
                .isInstanceOf(UserAccountService.BadCredentials.class);
    }

    @Test
    void passwordLongerThanBcryptLimitIsRejectedWithoutLookup() {
        assertThat(service.checkCredentials("analyst", "a".repeat(73)))
                .isInstanceOf(UserAccountService.BadCredentials.class);
        verifyNoInteractions(users);
    }

    @Test
    void findActiveActorIgnoresDisabledAccounts() {
        when(users.findById(2L)).thenReturn(Optional.of(storedUser(false)));
        when(users.findById(3L)).thenReturn(Optional.empty());

        assertThat(service.findActiveActor(2L)).isEmpty();
        assertThat(service.findActiveActor(3L)).isEmpty();
    }
}
