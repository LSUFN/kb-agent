package com.example.kbagent.observability;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统计概览（§6.5）：按业务时区的自然日聚合模型调用和工具调用。
 * 延迟和首字延迟只统计对话调用（CHAT）：向量化调用通常只要几十毫秒，混在一起会让百分位失真。
 * 合计行用 GROUPING SETS 在同一条 SQL 里算出，因为百分位不能由每天的百分位推出。
 */
@Service
public class MetricsQueryService {

    /** 一次最多查询的天数 */
    public static final int MAX_DAYS = 92;
    private static final int DEFAULT_DAYS = 7;

    /**
     * @param day 合计行为 null
     */
    public record MetricsDay(String day, long llmCalls, long llmFailures, long embeddingCalls, Long p50LatencyMs,
                             Long p95LatencyMs, Long p50FirstTokenMs, long promptTokens, long completionTokens,
                             long toolCalls, long toolDenied) {

        static MetricsDay empty(String day) {
            return new MetricsDay(day, 0, 0, 0, null, null, null, 0, 0, 0, 0);
        }

        MetricsDay withTools(long toolCalls, long toolDenied) {
            return new MetricsDay(day, llmCalls, llmFailures, embeddingCalls, p50LatencyMs, p95LatencyMs,
                    p50FirstTokenMs, promptTokens, completionTokens, toolCalls, toolDenied);
        }
    }

    private record ToolCounts(String day, long calls, long denied) {
    }

    /** @param from 包含；@param to 不包含 */
    public record MetricsOverview(LocalDate from, LocalDate to, String zone, MetricsDay total, List<MetricsDay> days) {
    }

    private static final String LLM_SQL = """
            SELECT d.day,
                   count(*) FILTER (WHERE d.call_type = 'CHAT') AS llm_calls,
                   count(*) FILTER (WHERE d.call_type = 'CHAT' AND NOT d.success) AS llm_failures,
                   count(*) FILTER (WHERE d.call_type = 'EMBEDDING') AS embedding_calls,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY d.latency_ms)
                       FILTER (WHERE d.call_type = 'CHAT') AS p50_latency,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY d.latency_ms)
                       FILTER (WHERE d.call_type = 'CHAT') AS p95_latency,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY d.first_token_ms)
                       FILTER (WHERE d.call_type = 'CHAT' AND d.first_token_ms IS NOT NULL) AS p50_first_token,
                   coalesce(sum(d.prompt_tokens) FILTER (WHERE d.call_type = 'CHAT'), 0) AS prompt_tokens,
                   coalesce(sum(d.completion_tokens) FILTER (WHERE d.call_type = 'CHAT'), 0) AS completion_tokens
              FROM (SELECT to_char(created_at AT TIME ZONE :zone, 'YYYY-MM-DD') AS day, call_type, success,
                           latency_ms, first_token_ms, prompt_tokens, completion_tokens
                      FROM llm_call_log
                     WHERE created_at >= :from AND created_at < :to) d
             GROUP BY GROUPING SETS ((d.day), ())
            """;

    private static final String TOOL_SQL = """
            SELECT t.day, count(*) AS tool_calls, count(*) FILTER (WHERE t.decision = 'DENIED') AS tool_denied
              FROM (SELECT to_char(created_at AT TIME ZONE :zone, 'YYYY-MM-DD') AS day, decision
                      FROM tool_call_log
                     WHERE created_at >= :from AND created_at < :to) t
             GROUP BY GROUPING SETS ((t.day), ())
            """;

    private final JdbcClient jdbc;
    private final ZoneId zone;
    private final Clock clock;

    public MetricsQueryService(JdbcClient jdbc, @Value("${app.business.zone:Asia/Shanghai}") ZoneId zone,
                               Clock clock) {
        this.jdbc = jdbc;
        this.zone = zone;
        this.clock = clock;
    }

    /**
     * @param from 包含；为 null 时取 to 之前 7 天
     * @param to   不包含；为 null 时取明天（即统计到今天为止）
     */
    @Transactional(readOnly = true)
    public MetricsOverview overview(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(zone)).plusDays(1);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS);
        if (!start.isBefore(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始日期必须早于结束日期");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "一次最多查询 " + MAX_DAYS + " 天");
        }
        Map<String, Object> params = Map.of(
                "zone", zone.getId(),
                "from", OffsetDateTime.ofInstant(start.atStartOfDay(zone).toInstant(), ZoneOffset.UTC),
                "to", OffsetDateTime.ofInstant(end.atStartOfDay(zone).toInstant(), ZoneOffset.UTC));

        Map<String, MetricsDay> llm = new HashMap<>();
        jdbc.sql(LLM_SQL).params(params).query((rs, n) -> new MetricsDay(rs.getString("day"),
                        rs.getLong("llm_calls"), rs.getLong("llm_failures"), rs.getLong("embedding_calls"),
                        roundedOrNull(rs.getObject("p50_latency")), roundedOrNull(rs.getObject("p95_latency")),
                        roundedOrNull(rs.getObject("p50_first_token")), rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens"), 0, 0))
                .list()
                .forEach(day -> llm.put(key(day.day()), day));
        Map<String, ToolCounts> tools = new HashMap<>();
        jdbc.sql(TOOL_SQL).params(params).query((rs, n) -> new ToolCounts(rs.getString("day"),
                        rs.getLong("tool_calls"), rs.getLong("tool_denied")))
                .list()
                .forEach(counts -> tools.put(key(counts.day()), counts));

        List<MetricsDay> days = new ArrayList<>();
        for (LocalDate d = start; d.isBefore(end); d = d.plusDays(1)) {
            days.add(merge(d.toString(), llm, tools));
        }
        return new MetricsOverview(start, end, zone.getId(), merge(null, llm, tools), days);
    }

    private static MetricsDay merge(String day, Map<String, MetricsDay> llm, Map<String, ToolCounts> tools) {
        MetricsDay base = llm.getOrDefault(key(day), MetricsDay.empty(day));
        ToolCounts counts = tools.get(key(day));
        return counts == null ? base : base.withTools(counts.calls(), counts.denied());
    }

    /** GROUPING SETS 的合计行 day 为 NULL，用空字符串作为它的键 */
    private static String key(String day) {
        return day == null ? "" : day;
    }

    private static Long roundedOrNull(Object value) {
        return value instanceof Number n ? Math.round(n.doubleValue()) : null;
    }
}
