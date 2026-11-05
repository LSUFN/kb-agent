package com.example.kbagent.observability;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.kbagent.common.api.PageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** 管理员查看调用日志（§6.5）。日志在写入时已经脱敏，这里返回的就是存储的内容。 */
@Service
public class CallLogQueryService {

    public record LlmCallFilter(String traceId, Long userId, Boolean success, Instant from, Instant to) {
    }

    public record ToolCallFilter(String traceId, Long userId, String toolName, ToolAuditRecorder.Decision decision,
                                 Instant from, Instant to) {
    }

    /** args 是写入时已脱敏的参数（JSON 字符串）。 */
    public record ToolCallView(long id, String traceId, String toolCallId, long userId, String userRole,
                               Long conversationId, String toolName, String args, List<String> effectiveScope,
                               String decision, boolean success, String resultSummary, String errorCode,
                               int latencyMs, Instant createdAt) {
    }

    public record LlmCallView(long id, String traceId, Long userId, Long conversationId, String callType,
                              String chatMode, String model, boolean streamed, Integer promptTokens,
                              Integer completionTokens, boolean usageEstimated, String inputPreview,
                              Integer outputChars, Integer firstTokenMs, int latencyMs, boolean success,
                              String errorCode, String errorMessage, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    public CallLogQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param page 从 1 开始 */
    public PageResponse<LlmCallView> llmCalls(LlmCallFilter filter, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (filter.traceId() != null && !filter.traceId().isBlank()) {
            where.append(" AND trace_id = :traceId");
            params.put("traceId", filter.traceId().trim());
        }
        if (filter.userId() != null) {
            where.append(" AND user_id = :userId");
            params.put("userId", filter.userId());
        }
        if (filter.success() != null) {
            where.append(" AND success = :success");
            params.put("success", filter.success());
        }
        if (filter.from() != null) {
            where.append(" AND created_at >= :from");
            params.put("from", OffsetDateTime.ofInstant(filter.from(), ZoneOffset.UTC));
        }
        if (filter.to() != null) {
            where.append(" AND created_at < :to");
            params.put("to", OffsetDateTime.ofInstant(filter.to(), ZoneOffset.UTC));
        }
        long total = jdbc.sql("SELECT count(*) FROM llm_call_log" + where).params(params).query(Long.class).single();
        List<LlmCallView> items = jdbc.sql("""
                        SELECT id, trace_id, user_id, conversation_id, call_type, chat_mode, model, streamed,
                               prompt_tokens, completion_tokens, usage_estimated, input_preview_masked, output_chars,
                               first_token_ms, latency_ms, success, error_code, error_message_masked, created_at
                          FROM llm_call_log""" + where + " ORDER BY id DESC OFFSET :offset LIMIT :limit")
                .params(params)
                .param("offset", (page - 1) * size)
                .param("limit", size)
                .query((rs, n) -> new LlmCallView(rs.getLong("id"), rs.getString("trace_id"),
                        (Long) rs.getObject("user_id"), (Long) rs.getObject("conversation_id"),
                        rs.getString("call_type"), rs.getString("chat_mode"), rs.getString("model"),
                        rs.getBoolean("streamed"), (Integer) rs.getObject("prompt_tokens"),
                        (Integer) rs.getObject("completion_tokens"), rs.getBoolean("usage_estimated"),
                        rs.getString("input_preview_masked"), (Integer) rs.getObject("output_chars"),
                        (Integer) rs.getObject("first_token_ms"), rs.getInt("latency_ms"), rs.getBoolean("success"),
                        rs.getString("error_code"), rs.getString("error_message_masked"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        return new PageResponse<>(items, page, size, total);
    }

    /** @param page 从 1 开始 */
    public PageResponse<ToolCallView> toolCalls(ToolCallFilter filter, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (filter.traceId() != null && !filter.traceId().isBlank()) {
            where.append(" AND trace_id = :traceId");
            params.put("traceId", filter.traceId().trim());
        }
        if (filter.userId() != null) {
            where.append(" AND user_id = :userId");
            params.put("userId", filter.userId());
        }
        if (filter.toolName() != null && !filter.toolName().isBlank()) {
            where.append(" AND tool_name = :toolName");
            params.put("toolName", filter.toolName().trim());
        }
        if (filter.decision() != null) {
            where.append(" AND decision = :decision");
            params.put("decision", filter.decision().name());
        }
        if (filter.from() != null) {
            where.append(" AND created_at >= :from");
            params.put("from", OffsetDateTime.ofInstant(filter.from(), ZoneOffset.UTC));
        }
        if (filter.to() != null) {
            where.append(" AND created_at < :to");
            params.put("to", OffsetDateTime.ofInstant(filter.to(), ZoneOffset.UTC));
        }
        long total = jdbc.sql("SELECT count(*) FROM tool_call_log" + where).params(params).query(Long.class).single();
        List<ToolCallView> items = jdbc.sql("""
                        SELECT id, trace_id, tool_call_id, user_id, user_role, conversation_id, tool_name,
                               args_masked::text AS args, effective_scope, decision, success, result_summary,
                               error_code, latency_ms, created_at
                          FROM tool_call_log""" + where + " ORDER BY id DESC OFFSET :offset LIMIT :limit")
                .params(params)
                .param("offset", (page - 1) * size)
                .param("limit", size)
                .query((rs, n) -> new ToolCallView(rs.getLong("id"), rs.getString("trace_id"),
                        rs.getString("tool_call_id"), rs.getLong("user_id"), rs.getString("user_role"),
                        (Long) rs.getObject("conversation_id"), rs.getString("tool_name"), rs.getString("args"),
                        scope(rs.getArray("effective_scope")), rs.getString("decision"), rs.getBoolean("success"),
                        rs.getString("result_summary"), rs.getString("error_code"), rs.getInt("latency_ms"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        return new PageResponse<>(items, page, size, total);
    }

    private static List<String> scope(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
