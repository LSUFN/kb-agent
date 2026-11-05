package com.example.kbagent.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AGENT 模式配置（app.agent.*，§4.4.2）。
 *
 * @param toolBudget      每轮最多执行的工具调用次数，超出后工具返回 BUDGET_EXCEEDED
 * @param maxToolAttempts 每轮累计尝试达到这个次数时终止本轮（包括被拒绝和超出预算的尝试），必须大于 toolBudget
 */
@ConfigurationProperties("app.agent")
public record AgentProperties(
        @DefaultValue("6") int toolBudget,
        @DefaultValue("10") int maxToolAttempts) {
}
