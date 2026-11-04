package com.example.kbagent.user.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.common.error.FieldViolation;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 系统用户（表 sys_user）。
 * <p>
 * 不变式：只有 ANALYST 有区域范围，且至少一个；USER 和 ADMIN 的区域范围为空（ADMIN 本身可访问全部区域）。
 * 所有修改角色或区域的路径都经过 {@link #checkScope}，因此不会写入违反不变式的数据。
 */
@Entity
@Table(name = "sys_user")
public class SysUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 64)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "region_scope", nullable = false, columnDefinition = "varchar(16)[]")
    private String[] regionScope = new String[0];

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SysUser() {
        // JPA
    }

    public static SysUser create(String username, String passwordHash, String displayName,
                                 Role role, Collection<Region> regionScope) {
        SysUser user = new SysUser();
        user.username = username;
        user.passwordHash = passwordHash;
        user.displayName = displayName;
        user.role = role;
        user.regionScope = toColumn(checkScope(role, regionScope));
        return user;
    }

    /**
     * 修改角色和/或区域范围，参数为 null 表示不修改该项。
     * 角色改为 USER/ADMIN 且未指定区域时自动清空区域；角色为 ANALYST 且未指定区域时沿用原区域。
     */
    public void changeRoleAndScope(Role newRole, Collection<Region> newScope) {
        Role targetRole = newRole != null ? newRole : role;
        Collection<Region> targetScope;
        if (newScope != null) {
            targetScope = newScope;
        } else if (targetRole == Role.ANALYST) {
            targetScope = regionScope();
        } else {
            targetScope = Set.of();
        }
        this.regionScope = toColumn(checkScope(targetRole, targetScope));
        this.role = targetRole;
    }

    public void rename(String newDisplayName) {
        this.displayName = newDisplayName;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Actor toActor() {
        return new Actor(id, username, displayName, role, regionScope());
    }

    /** 校验角色与区域范围的组合，返回规范化后的不可变集合。 */
    public static Set<Region> checkScope(Role role, Collection<Region> scope) {
        Set<Region> regions = Region.immutableCopy(scope);
        if (role == Role.ANALYST && regions.isEmpty()) {
            throw invalidScope("ANALYST 至少需要分配一个区域");
        }
        if (role != Role.ANALYST && !regions.isEmpty()) {
            throw invalidScope("只有 ANALYST 可以分配区域范围");
        }
        return regions;
    }

    private static BusinessException invalidScope(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, null,
                List.of(new FieldViolation("regionScope", message)));
    }

    private static String[] toColumn(Set<Region> regions) {
        return regions.stream().map(Enum::name).toArray(String[]::new);
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long id() {
        return id;
    }

    public String username() {
        return username;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public String displayName() {
        return displayName;
    }

    public Role role() {
        return role;
    }

    public Set<Region> regionScope() {
        return Region.immutableCopy(Arrays.stream(regionScope).map(Region::valueOf).toList());
    }

    public boolean enabled() {
        return enabled;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
