package com.example.kbagent.user.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.domain.SysUser;
import com.example.kbagent.user.repository.SysUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 供认证流程使用的账号查询：校验用户名口令、按 ID 加载当前有效的 Actor。
 * 口令哈希只在本模块内使用，不会交给 security 模块。
 */
@Service
public class UserAccountService {

    /** 口令校验结果。 */
    public sealed interface CredentialCheck permits Authenticated, BadCredentials, Disabled {
    }

    public record Authenticated(Actor actor) implements CredentialCheck {
    }

    public record BadCredentials() implements CredentialCheck {
    }

    /** 口令正确但账号已停用。只有在口令正确时才会返回，避免泄露账号状态。 */
    public record Disabled() implements CredentialCheck {
    }

    static final int BCRYPT_MAX_BYTES = 72;

    private final SysUserRepository users;
    private final PasswordEncoder passwordEncoder;
    /** 用户名不存在时也做一次哈希比对，使响应时间与“口令错误”一致，防止枚举用户名。 */
    private final String dummyHash;

    public UserAccountService(SysUserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode("timing-equalizer-not-a-real-password");
    }

    @Transactional(readOnly = true)
    public CredentialCheck checkCredentials(String username, String rawPassword) {
        if (rawPassword == null || rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            // 创建用户时已经限制在 72 字节以内，超长的输入不可能正确；BCrypt 遇到超长口令会直接抛异常
            return new BadCredentials();
        }
        Optional<SysUser> found = users.findByUsername(normalizeUsername(username));
        if (found.isEmpty()) {
            passwordEncoder.matches(rawPassword, dummyHash);
            return new BadCredentials();
        }
        SysUser user = found.get();
        if (!passwordEncoder.matches(rawPassword, user.passwordHash())) {
            return new BadCredentials();
        }
        if (!user.enabled()) {
            return new Disabled();
        }
        return new Authenticated(user.toActor());
    }

    /** 账号存在且已启用时返回 Actor；不存在或已停用时返回 empty。 */
    @Transactional(readOnly = true)
    public Optional<Actor> findActiveActor(long userId) {
        return users.findById(userId).filter(SysUser::enabled).map(SysUser::toActor);
    }

    /** 用户名不区分大小写：统一去掉首尾空白并转为小写后再存储和比较。 */
    public static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
