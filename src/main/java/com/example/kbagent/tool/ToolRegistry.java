package com.example.kbagent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Validator;

import com.example.kbagent.observability.ToolAuditRecorder;
import com.example.kbagent.observability.ToolAuditRecorder.Decision;
import com.example.kbagent.user.domain.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 全部工具的 {@link GuardedToolCallback}（每个工具一个实例，无状态，每轮的状态都在 ToolContext 里）。
 * <ul>
 *   <li>{@link #callbacksFor(Actor)}：组装请求时按角色只暴露有权限的工具，减少无效尝试；</li>
 *   <li>同时作为 Spring AI 的 {@link ToolCallbackResolver}（替换框架默认的解析器）：模型调用了没有暴露给它的工具时，
 *       框架会按名字来这里查找。返回的仍然是受保护的回调，由它按角色拒绝并记审计日志——
 *       暴露过滤不是安全边界，越权调用必须走到服务端校验。不存在的工具名返回一个只会报错的回调，不让整轮失败。</li>
 * </ul>
 */
@Component
public class ToolRegistry implements ToolCallbackResolver {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
    private final ToolPolicy policy;
    private final ToolAuditRecorder audit;
    private final ObjectMapper objectMapper;

    public ToolRegistry(List<AgentTool<?>> tools, ToolPolicy policy, Validator validator, ObjectMapper objectMapper,
                        ToolAuditRecorder audit) {
        this.policy = policy;
        this.audit = audit;
        this.objectMapper = objectMapper;
        for (AgentTool<?> tool : tools) {
            if (callbacks.put(tool.name(), guard(tool, policy, validator, objectMapper, audit)) != null) {
                throw new IllegalStateException("duplicate tool name " + tool.name());
            }
        }
    }

    private static <A extends Record> GuardedToolCallback<A> guard(AgentTool<A> tool, ToolPolicy policy,
                                                                   Validator validator, ObjectMapper objectMapper,
                                                                   ToolAuditRecorder audit) {
        return new GuardedToolCallback<>(tool, policy, validator, objectMapper, audit);
    }

    /** 当前用户可以使用的工具。 */
    public List<ToolCallback> callbacksFor(Actor actor) {
        return callbacks.entrySet().stream()
                .filter(e -> policy.allows(actor.role(), e.getKey()))
                .map(Map.Entry::getValue)
                .toList();
    }

    @Override
    public ToolCallback resolve(String toolName) {
        ToolCallback callback = callbacks.get(toolName);
        return callback != null ? callback : new UnknownToolCallback(toolName);
    }

    /** 模型调用了不存在的工具：计一次尝试、记审计日志，返回错误让模型改正。 */
    private final class UnknownToolCallback implements ToolCallback {

        private final ToolDefinition definition;

        UnknownToolCallback(String name) {
            this.definition = ToolDefinition.builder().name(name).description("").inputSchema("{}").build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            String name = definition.name();
            AgentRequestContext.from(toolContext).ifPresentOrElse(ctx -> {
                ctx.budget().recordAttempt();
                audit.record(new ToolAuditRecorder.Entry(ctx.traceId(), ctx.nextCallId(), ctx.actor().userId(),
                        ctx.actor().role().name(), ctx.conversationId(), name.length() <= 64 ? name
                        : name.substring(0, 64), Map.of(), List.of(), Decision.INVALID, false, "不存在的工具",
                        "UNKNOWN_TOOL", 0));
            }, () -> log.warn("unknown tool {} was called without AgentRequestContext", name));
            return objectMapper.writeValueAsString(Map.of("error", "UNKNOWN_TOOL",
                    "message", "不存在名为 " + name + " 的工具，请只使用提供的工具"));
        }
    }
}
