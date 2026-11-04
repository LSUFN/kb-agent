package com.example.kbagent.chat.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 对话配置（app.chat.*）。
 *
 * @param rateLimitPerMinute 每个用户每分钟最多提问次数（§4.7），流式和非流式接口共用
 * @param historyRounds      带给模型的历史轮数；只带完整成功的轮次
 * @param heartbeatInterval  SSE 心跳间隔（§6.4）；也决定了等待首字期间多久能发现客户端已断开
 * @param staleStreamTimeout 超过这个时间仍为 STREAMING 的消息视为进程崩溃遗留，改为 INTERRUPTED（§4.3）。
 *                           必须大于 spring.mvc.async.request-timeout，否则会误伤正在输出的回答
 */
@ConfigurationProperties("app.chat")
public record ChatProperties(
        @DefaultValue("20") int rateLimitPerMinute,
        @DefaultValue("3") int historyRounds,
        @DefaultValue("15s") Duration heartbeatInterval,
        @DefaultValue("10m") Duration staleStreamTimeout) {
}
