package com.example.kbagent.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.observability.MetricsQueryService.MetricsDay;
import com.example.kbagent.observability.MetricsQueryService.MetricsOverview;
import com.example.kbagent.support.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 统计概览的 SQL（§6.5）：按 Asia/Shanghai 的自然日分组、百分位、只统计对话调用的延迟、合计行、补零。
 * 数据放在 2020 年 3 月，其他测试不会写到这段时间。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class MetricsQueryServiceIT {

    private static final LocalDate FROM = LocalDate.parse("2020-03-01");
    private static final LocalDate TO = LocalDate.parse("2020-03-04");

    @Autowired
    MetricsQueryService metrics;

    @Autowired
    JdbcTemplate jdbc;

    private void llm(String createdAt, String type, int latencyMs, Integer firstTokenMs, boolean success,
                     Integer promptTokens, Integer completionTokens) {
        jdbc.update("""
                        INSERT INTO llm_call_log(trace_id, call_type, model, latency_ms, first_token_ms, success,
                                                 prompt_tokens, completion_tokens, created_at)
                        VALUES ('metrics-it', ?, 'fake', ?, ?, ?, ?, ?, ?)""",
                type, latencyMs, firstTokenMs, success, promptTokens, completionTokens,
                Timestamp.from(Instant.parse(createdAt)));
    }

    private void tool(String createdAt, String decision) {
        jdbc.update("""
                        INSERT INTO tool_call_log(trace_id, user_id, user_role, tool_name, decision, success,
                                                  latency_ms, created_at)
                        VALUES ('metrics-it', 1, 'ANALYST', 'countAnomalies', ?, ?, 10, ?)""",
                decision, "ALLOWED".equals(decision), Timestamp.from(Instant.parse(createdAt)));
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM llm_call_log WHERE trace_id = 'metrics-it'");
        jdbc.update("DELETE FROM tool_call_log WHERE trace_id = 'metrics-it'");
        // 3 月 1 日（北京时间）：5 次对话调用，其中 1 次失败；1 次向量化调用
        llm("2020-03-01T01:00:00Z", "CHAT", 100, 50, true, 1000, 100);
        llm("2020-03-01T02:00:00Z", "CHAT", 200, 70, true, 1000, 100);
        llm("2020-03-01T03:00:00Z", "CHAT", 300, null, true, 1000, 100);
        llm("2020-03-01T04:00:00Z", "CHAT", 400, null, false, null, null);
        // 15:30Z 是北京时间 23:30，仍然算 3 月 1 日
        llm("2020-03-01T15:30:00Z", "CHAT", 1000, 90, true, 500, 50);
        llm("2020-03-01T05:00:00Z", "EMBEDDING", 5, null, true, 20, null);
        // 16:30Z 是北京时间 3 月 2 日 00:30
        llm("2020-03-01T16:30:00Z", "CHAT", 2000, 300, true, 100, 10);
        tool("2020-03-01T02:00:01Z", "ALLOWED");
        tool("2020-03-01T02:00:02Z", "DENIED");
        tool("2020-03-01T02:00:03Z", "INVALID");
        tool("2020-03-01T16:30:01Z", "ALLOWED");
        // 范围之外
        llm("2020-03-04T00:00:00Z", "CHAT", 9999, 9999, true, 9999, 9999);
    }

    @Test
    void aggregatesByBusinessDayWithPercentilesOverChatCallsOnly() {
        MetricsOverview overview = metrics.overview(FROM, TO);

        assertThat(overview.zone()).isEqualTo("Asia/Shanghai");
        assertThat(overview.days()).extracting(MetricsDay::day)
                .containsExactly("2020-03-01", "2020-03-02", "2020-03-03");

        MetricsDay first = overview.days().get(0);
        assertThat(first.llmCalls()).isEqualTo(5);
        assertThat(first.llmFailures()).isEqualTo(1);
        assertThat(first.embeddingCalls()).isEqualTo(1);
        // percentile_cont：[100, 200, 300, 400, 1000] 的 P50 = 300，P95 = 400 + 0.8 × (1000 − 400) = 880
        assertThat(first.p50LatencyMs()).isEqualTo(300);
        assertThat(first.p95LatencyMs()).isEqualTo(880);
        // 首字延迟只看有值的：[50, 70, 90]
        assertThat(first.p50FirstTokenMs()).isEqualTo(70);
        assertThat(first.promptTokens()).isEqualTo(3500);
        assertThat(first.completionTokens()).isEqualTo(350);
        assertThat(first.toolCalls()).isEqualTo(3);
        assertThat(first.toolDenied()).isEqualTo(1);

        MetricsDay second = overview.days().get(1);
        assertThat(second.llmCalls()).isEqualTo(1);
        assertThat(second.p50LatencyMs()).isEqualTo(2000);
        assertThat(second.toolCalls()).isEqualTo(1);

        // 没有数据的日子补零
        MetricsDay empty = overview.days().get(2);
        assertThat(empty.llmCalls()).isZero();
        assertThat(empty.p50LatencyMs()).isNull();

        // 合计行的百分位按全部对话调用重新计算：[100, 200, 300, 400, 1000, 2000]
        MetricsDay total = overview.total();
        assertThat(total.day()).isNull();
        assertThat(total.llmCalls()).isEqualTo(6);
        assertThat(total.p50LatencyMs()).isEqualTo(350);
        assertThat(total.toolCalls()).isEqualTo(4);
        assertThat(total.toolDenied()).isEqualTo(1);
    }

    @Test
    void rejectsEmptyOrTooLongRanges() {
        assertThatThrownBy(() -> metrics.overview(TO, FROM))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> metrics.overview(FROM, FROM.plusDays(MetricsQueryService.MAX_DAYS + 1)))
                .isInstanceOf(BusinessException.class);
        assertThat(metrics.overview(FROM, FROM.plusDays(MetricsQueryService.MAX_DAYS)).days())
                .hasSize(MetricsQueryService.MAX_DAYS);
    }
}
