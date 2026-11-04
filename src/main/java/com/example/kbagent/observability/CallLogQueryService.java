package com.example.kbagent.observability;

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
}
