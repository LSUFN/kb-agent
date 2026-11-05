package com.example.kbagent.tool;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一轮对话的工具调用预算（§4.4.2）：
 * <ul>
 *   <li>最多执行 {@code maxCalls} 次，超出后工具不再执行，只返回 BUDGET_EXCEEDED，让模型基于已有结果作答；</li>
 *   <li>累计尝试（包括被拒绝、参数不合法、超出预算的）达到 {@code maxAttempts} 次时抛出
 *       {@link AgentAbortException} 终止本轮，防止模型陷入无休止的重试。</li>
 * </ul>
 */
public final class ToolBudget {

    private final int maxCalls;
    private final int maxAttempts;
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger executions = new AtomicInteger();

    public ToolBudget(int maxCalls, int maxAttempts) {
        if (maxCalls < 1 || maxAttempts <= maxCalls) {
            throw new IllegalArgumentException("require 1 <= maxCalls < maxAttempts");
        }
        this.maxCalls = maxCalls;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 记一次尝试。必须在执行被包装的工具之前调用。
     *
     * @throws AgentAbortException 这是第 maxAttempts 次尝试
     */
    public void recordAttempt() {
        int attempt = attempts.incrementAndGet();
        if (attempt >= maxAttempts) {
            throw new AgentAbortException(attempt);
        }
    }

    /** 尝试占用一次执行名额；名额用完时返回 false。 */
    public boolean tryConsume() {
        return executions.getAndUpdate(n -> n < maxCalls ? n + 1 : n) < maxCalls;
    }

    public int maxCalls() {
        return maxCalls;
    }

    public int executions() {
        return executions.get();
    }

    public int attempts() {
        return attempts.get();
    }
}
