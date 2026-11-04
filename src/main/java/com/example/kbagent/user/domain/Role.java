package com.example.kbagent.user.domain;

/**
 * 系统角色（ARCHITECTURE.md §1.1）。在 Spring Security 中对应的权限是 {@code ROLE_ + name()}。
 */
public enum Role {

    /** 普通员工：知识库问答。 */
    USER,
    /** 业务分析员：在 USER 的基础上可以使用业务分析工具，只能访问分配给自己的区域数据。 */
    ANALYST,
    /** 管理员：用户管理、全部知识库、日志查询；业务数据不受区域限制。 */
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
