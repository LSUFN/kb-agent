package com.example.kbagent.tool;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.kbagent.user.domain.Actor;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 一轮 AGENT 对话的服务端上下文（§4.4.1）。由服务端根据 JWT 和数据库中的用户记录构建，经 ToolContext 传给工具，
 * 不会发送给模型：模型无论输出什么参数，都改变不了“我是谁”和“我能看什么”。
 * <p>
 * 工具在 Reactor 线程上执行（S4），读不到 SecurityContext 和 MDC，需要的信息一律从这里获取。
 *
 * @param accessibleKbIds 本轮可以检索的知识库；会话绑定了知识库时只有它一个
 */
public record AgentRequestContext(Actor actor, List<Long> accessibleKbIds, Long conversationId, String traceId,
                                  ToolBudget budget, ToolEventSink events, CitationRegistry citations,
                                  AtomicInteger callSequence) {

    /** 在 ToolContext 中的键。 */
    public static final String KEY = "agentRequestContext";

    public AgentRequestContext(Actor actor, List<Long> accessibleKbIds, Long conversationId, String traceId,
                               ToolBudget budget, ToolEventSink events, CitationRegistry citations) {
        this(actor, List.copyOf(accessibleKbIds), conversationId, traceId, budget, events, citations,
                new AtomicInteger());
    }

    /** 本轮的下一个工具调用编号：c1、c2…… */
    public String nextCallId() {
        return "c" + callSequence.incrementAndGet();
    }

    public Map<String, Object> asToolContext() {
        return Map.of(KEY, this);
    }

    public static Optional<AgentRequestContext> from(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return Optional.empty();
        }
        return toolContext.getContext().get(KEY) instanceof AgentRequestContext ctx ? Optional.of(ctx)
                : Optional.empty();
    }
}
