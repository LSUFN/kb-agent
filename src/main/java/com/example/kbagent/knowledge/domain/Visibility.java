package com.example.kbagent.knowledge.domain;

public enum Visibility {
    /** 只有所有者、成员和 ADMIN 可以访问。 */
    PRIVATE,
    /** 所有登录用户都可以阅读和检索。 */
    PUBLIC
}
