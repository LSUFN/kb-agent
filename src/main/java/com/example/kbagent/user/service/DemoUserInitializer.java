package com.example.kbagent.user.service;

import java.util.List;
import java.util.Set;

import com.example.kbagent.user.domain.Region;
import com.example.kbagent.user.domain.Role;
import com.example.kbagent.user.domain.SysUser;
import com.example.kbagent.user.repository.SysUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时创建三种角色的演示账号（app.demo.seed-users=true 时生效）。
 * <p>
 * 只在账号不存在时创建，不会覆盖已有账号的密码。没有放进 Flyway：初始密码来自环境变量，
 * 写进迁移脚本的占位符会让明文进入 SQL，修改密码后还会导致迁移校验和变化。
 */
@Component
@ConditionalOnBooleanProperty("app.demo.seed-users")
@EnableConfigurationProperties(DemoProperties.class)
class DemoUserInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserInitializer.class);

    private record DemoUser(String username, String displayName, Role role, Set<Region> regionScope) {
    }

    private static final List<DemoUser> DEMO_USERS = List.of(
            new DemoUser("admin", "系统管理员", Role.ADMIN, Set.of()),
            new DemoUser("analyst", "华东分析员", Role.ANALYST, Set.of(Region.EAST)),
            new DemoUser("user", "普通员工", Role.USER, Set.of()));

    private final SysUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final DemoProperties properties;

    DemoUserInitializer(SysUserRepository users, PasswordEncoder passwordEncoder, DemoProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String password = properties.password();
        if (password == null || password.length() < 8) {
            throw new IllegalStateException(
                    "app.demo.seed-users=true 时必须设置至少 8 位的演示账号密码（环境变量 DEMO_USER_PASSWORD）");
        }
        for (DemoUser demo : DEMO_USERS) {
            if (users.existsByUsername(demo.username())) {
                continue;
            }
            users.save(SysUser.create(demo.username(), passwordEncoder.encode(password), demo.displayName(),
                    demo.role(), demo.regionScope()));
            log.info("已创建演示账号 {}（{}）", demo.username(), demo.role());
        }
    }
}
