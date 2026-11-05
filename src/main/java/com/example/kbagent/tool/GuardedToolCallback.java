package com.example.kbagent.tool;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import com.example.kbagent.business.service.DataScope.ScopeDeniedException;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.common.error.RateLimitedException;
import com.example.kbagent.observability.ArgumentMasker;
import com.example.kbagent.observability.SensitiveDataMasker;
import com.example.kbagent.observability.ToolAuditRecorder;
import com.example.kbagent.observability.ToolAuditRecorder.Decision;
import com.example.kbagent.user.domain.Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;

/**
 * 包装一个 {@link AgentTool}，每次调用都按固定顺序执行（§4.4.3）：
 * <ol>
 *   <li>从 ToolContext 取出 {@link AgentRequestContext}，取不到就拒绝（fail-closed）；记一次尝试，达到上限时终止本轮</li>
 *   <li>按角色检查能否使用该工具（不依赖“只暴露了有权限的工具”）</li>
 *   <li>反序列化参数，做 Jakarta Validation 和工具特有的校验</li>
 *   <li>收敛数据范围：请求的区域、知识库与用户的范围求交集，为空就拒绝</li>
 *   <li>扣减调用预算，用完时不再执行</li>
 *   <li>推送 tool START 事件，执行</li>
 *   <li>写审计日志（参数脱敏），推送 tool END 事件</li>
 * </ol>
 * 被拒绝、参数不合法、超出预算时返回结构化的错误（例如 {@code {"error":"FORBIDDEN_SCOPE","message":"..."}}），
 * 让模型向用户解释原因，而不是抛异常。只有尝试次数达到上限时抛出 {@link AgentAbortException}，
 * 而且是在调用被包装的工具之前抛出（S4：这样才能终止工具循环）。
 */
public final class GuardedToolCallback<A extends Record> implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(GuardedToolCallback.class);
    private static final int MAX_ERROR_DETAIL = 200;

    private final AgentTool<A> tool;
    private final ToolPolicy policy;
    private final Validator validator;
    private final ObjectMapper argsMapper;
    private final ObjectMapper resultMapper;
    private final ToolAuditRecorder audit;
    private final ToolDefinition definition;

    public GuardedToolCallback(AgentTool<A> tool, ToolPolicy policy, Validator validator, ObjectMapper objectMapper,
                               ToolAuditRecorder audit) {
        this.tool = tool;
        this.policy = policy;
        this.validator = validator;
        // 模型偶尔会把枚举写成小写（例如 "east"），按大小写不敏感解析
        this.argsMapper = objectMapper.rebuild().enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS).build();
        this.resultMapper = objectMapper;
        this.audit = audit;
        // 参数 record 同时是 JSON Schema 的来源：@ToolParam 提供描述和是否必填
        this.definition = ToolDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(JsonSchemaGenerator.generateForType(tool.argsType()))
                .build();
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
        long startNanos = System.nanoTime();
        // 1. 上下文（fail-closed）和尝试次数
        AgentRequestContext ctx = AgentRequestContext.from(toolContext).orElse(null);
        if (ctx == null) {
            log.warn("tool {} was called without AgentRequestContext, refused", tool.name());
            return error("NO_CONTEXT", "缺少服务端调用上下文，已拒绝执行");
        }
        Invocation call = new Invocation(ctx, startNanos);
        try {
            ctx.budget().recordAttempt();
        } catch (AgentAbortException e) {
            call.audit(Map.of(), Set.of(), Decision.BUDGET_EXCEEDED, false, "尝试次数达到上限，终止本轮",
                    ErrorCode.AGENT_ABORTED.name());
            throw e;
        }
        // 2. 角色
        if (!policy.allows(ctx.actor().role(), tool.name())) {
            return call.reject(Map.of(), Decision.DENIED, "FORBIDDEN_TOOL",
                    "当前角色（" + ctx.actor().role() + "）无权使用工具 " + tool.name());
        }
        // 3. 参数
        A args;
        try {
            args = argsMapper.readValue(toolInput == null || toolInput.isBlank() ? "{}" : toolInput,
                    tool.argsType());
        } catch (JacksonException e) {
            return call.reject(Map.of(), Decision.INVALID, "INVALID_ARGUMENTS",
                    "参数格式不正确：" + detail(e.getOriginalMessage()));
        }
        Map<String, Object> maskedArgs = ArgumentMasker.mask(args);
        String violation = violationOf(args);
        if (violation != null) {
            return call.reject(maskedArgs, Decision.INVALID, "INVALID_ARGUMENTS", violation);
        }
        // 4. 数据范围
        Set<Region> scope;
        try {
            scope = tool.narrow(args, ctx);
        } catch (ScopeDeniedException e) {
            return call.reject(maskedArgs, Decision.DENIED, "FORBIDDEN_SCOPE", e.getMessage());
        }
        // 5. 调用预算
        if (!ctx.budget().tryConsume()) {
            return call.reject(maskedArgs, Decision.BUDGET_EXCEEDED, "BUDGET_EXCEEDED",
                    "本轮工具调用次数已用完（最多 " + ctx.budget().maxCalls() + " 次），请基于已有结果直接回答");
        }
        // 6. 执行
        ctx.events().started(call.callId, tool.name(), maskedArgs);
        try {
            AgentTool.Output output = tool.execute(args, scope, ctx);
            call.end(maskedArgs, scope, Decision.ALLOWED, true, output.summary(), null);
            return resultMapper.writeValueAsString(output.result());
        } catch (ScopeDeniedException e) {
            // 业务 Service 内的二次校验拒绝了（正常情况下第 4 步已经拦住）
            call.end(maskedArgs, scope, Decision.DENIED, false, e.getMessage(), "FORBIDDEN_SCOPE");
            return error("FORBIDDEN_SCOPE", e.getMessage());
        } catch (RateLimitedException e) {
            call.end(maskedArgs, scope, Decision.ALLOWED, false, "触发限流", ErrorCode.RATE_LIMITED.name());
            return error(ErrorCode.RATE_LIMITED.name(), "操作过于频繁，请 " + e.retryAfterSeconds() + " 秒后再试");
        } catch (BusinessException e) {
            Decision decision = e.errorCode() == ErrorCode.VALIDATION_FAILED ? Decision.INVALID : Decision.ALLOWED;
            call.end(maskedArgs, scope, decision, false, e.getMessage(), e.errorCode().name());
            return error(e.errorCode().name(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("tool {} failed (traceId={}, callId={}): {}", tool.name(), ctx.traceId(), call.callId,
                    e.toString());
            call.end(maskedArgs, scope, Decision.ALLOWED, false, "执行失败", "TOOL_ERROR");
            return error("TOOL_ERROR", "工具执行失败，请稍后再试或换个问法");
        }
    }

    /** Jakarta Validation 和工具特有的校验；没有问题时返回 null。 */
    private String violationOf(A args) {
        Set<ConstraintViolation<A>> violations = validator.validate(args);
        if (!violations.isEmpty()) {
            return violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("；"));
        }
        return tool.validate(args);
    }

    private String error(String code, String message) {
        return resultMapper.writeValueAsString(Map.of("error", code, "message", message));
    }

    /** 解析错误的详情可能回显模型给出的参数，先脱敏再截断。 */
    private static String detail(String message) {
        String masked = SensitiveDataMasker.mask(message == null ? "" : message);
        return masked.length() <= MAX_ERROR_DETAIL ? masked : masked.substring(0, MAX_ERROR_DETAIL) + "…";
    }

    /** 一次调用的编号、计时、审计和事件。 */
    private final class Invocation {

        private final AgentRequestContext ctx;
        private final long startNanos;
        private final String callId;

        Invocation(AgentRequestContext ctx, long startNanos) {
            this.ctx = ctx;
            this.startNanos = startNanos;
            this.callId = ctx.nextCallId();
        }

        /** 没有执行就结束：推送一对 START / END，写审计，返回结构化的错误。 */
        String reject(Map<String, Object> maskedArgs, Decision decision, String code, String message) {
            ctx.events().started(callId, tool.name(), maskedArgs);
            end(maskedArgs, Set.of(), decision, false, message, code);
            return error(code, message);
        }

        void end(Map<String, Object> maskedArgs, Set<Region> scope, Decision decision, boolean ok, String summary,
                 String errorCode) {
            long latencyMs = audit(maskedArgs, scope, decision, ok, summary, errorCode);
            ctx.events().finished(callId, tool.name(), maskedArgs, decision, ok, latencyMs, summary);
        }

        long audit(Map<String, Object> maskedArgs, Collection<Region> scope, Decision decision, boolean ok,
                   String summary, String errorCode) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            audit.record(new ToolAuditRecorder.Entry(ctx.traceId(), callId, ctx.actor().userId(),
                    ctx.actor().role().name(), ctx.conversationId(), tool.name(), maskedArgs,
                    scope.stream().map(Region::name).toList(), decision, ok, summary, errorCode, latencyMs));
            return latencyMs;
        }
    }
}
