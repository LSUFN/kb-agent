package com.example.kbagent.user.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 演示账号配置（app.demo.*）。
 *
 * @param seedUsers 启动时是否创建演示账号（local profile 默认开启）
 * @param password  演示账号的初始密码，只能通过环境变量 DEMO_USER_PASSWORD 注入
 */
@ConfigurationProperties("app.demo")
public record DemoProperties(boolean seedUsers, String password) {

    @Override
    public String toString() {
        return "DemoProperties[seedUsers=" + seedUsers + ", password=******]";
    }
}
