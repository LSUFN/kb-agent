package com.example.kbagent.observability;

import java.util.Collection;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 写入 tool_call_log（§4.4.3 第 7 步、§4.6）。参数在调用方已经按 {@link Sensitive} 脱敏，
 * 结果只记录摘要（行数、合计值），不记录明细。写日志失败只记一条告警，不影响工具调用。
 */
@Component
public class ToolAuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(ToolAuditRecorder.class);
    private static final int SUMMARY_LENGTH = 500;

    /** 与表上 decision 列的 CHECK 约束一致。 */
    public enum Decision {
        ALLOWED,
        DENIED,
        INVALID,
        BUDGET_EXCEEDED
    }

    /**
     * @param effectiveScope 收敛之后实际生效的区域范围；与区域无关的工具为空
     * @param success        工具是否执行成功；DENIED / INVALID / BUDGET_EXCEEDED 时为 false
     */
    public record Entry(String traceId, String toolCallId, long userId, String userRole, Long conversationId,
                        String toolName, Map<String, Object> maskedArgs, Collection<String> effectiveScope,
                        Decision decision, boolean success, String resultSummary, String errorCode,
                        long latencyMs) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ToolAuditRecorder(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void record(Entry e) {
        try {
            String summary = SensitiveDataMasker.mask(e.resultSummary());
            jdbc.sql("""
                            INSERT INTO tool_call_log(trace_id, tool_call_id, user_id, user_role, conversation_id,
                                tool_name, args_masked, effective_scope, decision, success, result_summary,
                                error_code, latency_ms)
                            VALUES (:traceId, :toolCallId, :userId, :userRole, :conversationId, :toolName,
                                CAST(:args AS jsonb), :scope, :decision, :success, :summary, :errorCode, :latencyMs)""")
                    .param("traceId", e.traceId())
                    .param("toolCallId", e.toolCallId())
                    .param("userId", e.userId())
                    .param("userRole", e.userRole())
                    .param("conversationId", e.conversationId())
                    .param("toolName", e.toolName())
                    .param("args", objectMapper.writeValueAsString(e.maskedArgs()))
                    .param("scope", e.effectiveScope() == null ? new String[0]
                            : e.effectiveScope().toArray(String[]::new))
                    .param("decision", e.decision().name())
                    .param("success", e.success())
                    .param("summary", summary == null || summary.length() <= SUMMARY_LENGTH
                            ? summary : summary.substring(0, SUMMARY_LENGTH))
                    .param("errorCode", e.errorCode())
                    .param("latencyMs", (int) Math.min(Integer.MAX_VALUE, e.latencyMs()))
                    .update();
        } catch (RuntimeException ex) {
            log.warn("failed to write tool_call_log (traceId={}, tool={})", e.traceId(), e.toolName(), ex);
        }
    }
}
