package com.example.kbagent.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.domain.Region;
import com.example.kbagent.user.domain.Role;
import com.example.kbagent.user.domain.SysUser;
import com.example.kbagent.user.domain.UserAccountChangedEvent;
import com.example.kbagent.user.repository.SysUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    private static final Actor OPERATOR = new Actor(1L, "admin", "管理员", Role.ADMIN, Set.of());

    @Mock
    SysUserRepository users;

    @Mock
    ApplicationEventPublisher events;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private AdminUserService service;

    @BeforeEach
    void setUp() {
        service = new AdminUserService(users, encoder, events);
    }

    private static SysUser stored(long id, Role role, Set<Region> regions) {
        SysUser user = SysUser.create("u" + id, "hash", "User " + id, role, regions);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static void assertErrorCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(expected));
    }

    @Test
    void createNormalizesUsernameAndStoresOnlyThePasswordHash() {
        when(users.existsByUsername("bob")).thenReturn(false);
        ArgumentCaptor<SysUser> saved = ArgumentCaptor.forClass(SysUser.class);
        when(users.saveAndFlush(saved.capture())).thenAnswer(inv -> {
            SysUser user = inv.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 10L);
            return user;
        });

        UserView view = service.create(new CreateUserCommand(" Bob ", "Passw0rd!", " Bob ", Role.USER, null));

        assertThat(view.username()).isEqualTo("bob");
        assertThat(view.displayName()).isEqualTo("Bob");
        assertThat(saved.getValue().passwordHash()).isNotEqualTo("Passw0rd!");
        assertThat(encoder.matches("Passw0rd!", saved.getValue().passwordHash())).isTrue();
    }

    @Test
    void duplicateUsernameIs409() {
        when(users.existsByUsername("bob")).thenReturn(true);

        assertErrorCode(() -> service.create(new CreateUserCommand("bob", "Passw0rd!", "Bob", Role.USER, null)),
                ErrorCode.USERNAME_TAKEN);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void concurrentDuplicateCaughtByUniqueConstraintIsAlso409() {
        when(users.existsByUsername("bob")).thenReturn(false);
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_sys_user_username"));

        assertErrorCode(() -> service.create(new CreateUserCommand("bob", "Passw0rd!", "Bob", Role.USER, null)),
                ErrorCode.USERNAME_TAKEN);
    }

    @Test
    void passwordOverBcryptByteLimitIsRejected() {
        // 25 个汉字 = 75 个 UTF-8 字节：字符数满足 8-64，但超过 BCrypt 的 72 字节上限
        assertErrorCode(() -> service.create(new CreateUserCommand("bob", "密".repeat(25), "Bob", Role.USER, null)),
                ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(users);
    }

    @Test
    void createEnforcesRoleScopeInvariant() {
        when(users.existsByUsername("ana")).thenReturn(false);

        assertErrorCode(() -> service.create(new CreateUserCommand("ana", "Passw0rd!", "Ana", Role.ANALYST, Set.of())),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void updatingUnknownUserIs404() {
        when(users.findById(99L)).thenReturn(Optional.empty());

        assertErrorCode(() -> service.update(99L, new UpdateUserCommand(null, null, null, false), OPERATOR),
                ErrorCode.NOT_FOUND);
    }

    @Test
    void adminCannotDisableSelfOrChangeOwnRole() {
        when(users.findById(1L)).thenReturn(Optional.of(stored(1L, Role.ADMIN, Set.of())));

        assertErrorCode(() -> service.update(1L, new UpdateUserCommand(null, null, null, false), OPERATOR),
                ErrorCode.CONFLICT);
        assertErrorCode(() -> service.update(1L, new UpdateUserCommand(null, Role.USER, null, null), OPERATOR),
                ErrorCode.CONFLICT);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void adminMayStillRenameSelf() {
        when(users.findById(1L)).thenReturn(Optional.of(stored(1L, Role.ADMIN, Set.of())));

        UserView view = service.update(1L, new UpdateUserCommand("新名字", Role.ADMIN, null, true), OPERATOR);

        assertThat(view.displayName()).isEqualTo("新名字");
    }

    @Test
    void updateAppliesChangesAndAnnouncesThem() {
        when(users.findById(2L)).thenReturn(Optional.of(stored(2L, Role.ANALYST, Set.of(Region.EAST))));

        UserView view = service.update(2L, new UpdateUserCommand(null, Role.USER, null, false), OPERATOR);

        assertThat(view.role()).isEqualTo(Role.USER);
        assertThat(view.regionScope()).isEmpty();
        assertThat(view.enabled()).isFalse();
        verify(users).flush();
        verify(events).publishEvent(new UserAccountChangedEvent(2L));
    }
}
