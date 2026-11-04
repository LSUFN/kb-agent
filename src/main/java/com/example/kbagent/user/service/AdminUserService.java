package com.example.kbagent.user.service;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.common.error.FieldViolation;
import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.domain.SysUser;
import com.example.kbagent.user.domain.UserAccountChangedEvent;
import com.example.kbagent.user.repository.SysUserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员的用户管理（/admin/users）。调用方的 ADMIN 角色由安全配置和 Controller 上的注解保证。
 */
@Service
public class AdminUserService {

    private final SysUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;

    public AdminUserService(SysUserRepository users, PasswordEncoder passwordEncoder,
                            ApplicationEventPublisher events) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
    }

    @Transactional
    public UserView create(CreateUserCommand command) {
        String username = UserAccountService.normalizeUsername(command.username());
        // BCrypt 只处理口令的前 72 个字节，超出部分会被忽略，因此直接拒绝
        if (command.password().getBytes(StandardCharsets.UTF_8).length > UserAccountService.BCRYPT_MAX_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, null,
                    List.of(new FieldViolation("password", "密码不能超过 72 个字节")));
        }
        if (users.existsByUsername(username)) {
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        SysUser user = SysUser.create(username, passwordEncoder.encode(command.password()),
                command.displayName().trim(), command.role(), command.regionScope());
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // 两个请求并发创建同名用户时，由数据库唯一约束兜底
            throw new BusinessException(ErrorCode.USERNAME_TAKEN);
        }
        return UserView.of(user);
    }

    /** @param page 从 1 开始 */
    @Transactional(readOnly = true)
    public PageResponse<UserView> list(String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by("id"));
        Page<SysUser> result = (keyword == null || keyword.isBlank())
                ? users.findAll(pageable)
                : users.findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
                        keyword.trim(), keyword.trim(), pageable);
        return PageResponse.of(result, UserView::of);
    }

    @Transactional(readOnly = true)
    public UserView get(long id) {
        return UserView.of(load(id));
    }

    @Transactional
    public UserView update(long id, UpdateUserCommand command, Actor operator) {
        SysUser user = load(id);
        if (operator.userId() == id) {
            boolean disablingSelf = Boolean.FALSE.equals(command.enabled());
            boolean changingOwnRole = command.role() != null && command.role() != user.role();
            if (disablingSelf || changingOwnRole) {
                // 防止管理员把自己锁在系统外；同时保证系统里至少保留一个可用的 ADMIN
                throw new BusinessException(ErrorCode.CONFLICT, "不能停用自己的账号或修改自己的角色");
            }
        }
        if (command.displayName() != null) {
            user.rename(command.displayName().trim());
        }
        if (command.role() != null || command.regionScope() != null) {
            user.changeRoleAndScope(command.role(), command.regionScope());
        }
        if (command.enabled() != null) {
            user.setEnabled(command.enabled());
        }
        users.flush();
        // 监听方在事务提交后清除该用户的状态缓存，使停用、改角色立即生效
        events.publishEvent(new UserAccountChangedEvent(id));
        return UserView.of(user);
    }

    private SysUser load(long id) {
        return users.findById(id).orElseThrow(() -> BusinessException.notFound("用户"));
    }
}
