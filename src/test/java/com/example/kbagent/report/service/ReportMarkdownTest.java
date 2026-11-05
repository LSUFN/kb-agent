package com.example.kbagent.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import com.example.kbagent.business.domain.AnomalyGroupBy;
import com.example.kbagent.business.domain.OrderGroupBy;
import com.example.kbagent.business.repository.BizStatsRepository.AnomalyRow;
import com.example.kbagent.business.repository.BizStatsRepository.OrderRow;
import com.example.kbagent.business.service.BizStats.AnomalyStats;
import com.example.kbagent.business.service.BizStats.OrderStats;
import com.example.kbagent.user.domain.Region;
import org.junit.jupiter.api.Test;

/** ADR-10：报告由 Java 确定性生成，每个数字都来自传入的统计结果。 */
class ReportMarkdownTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 31);
    private static final Set<Region> EAST = Set.of(Region.EAST);
    private static final ReportMarkdown.Header HEADER = new ReportMarkdown.Header("订单经营报告（测试）", FROM, TO,
            EAST, "Asia/Shanghai", ZonedDateTime.of(2026, 11, 5, 9, 30, 0, 0, ZoneId.of("Asia/Shanghai")), "华东分析员");

    @Test
    void formatsNumbersConsistently() {
        assertThat(ReportMarkdown.count(1234567)).isEqualTo("1,234,567");
        assertThat(ReportMarkdown.amount(new BigDecimal("1234567.895"))).isEqualTo("1,234,567.90");
        assertThat(ReportMarkdown.amount(BigDecimal.ZERO)).isEqualTo("0.00");
        assertThat(ReportMarkdown.rate(1, 3)).isEqualTo("33.3%");
        assertThat(ReportMarkdown.rate(0, 0)).isEqualTo("-");
        assertThat(ReportMarkdown.minutes(12.345)).isEqualTo("12.3");
        assertThat(ReportMarkdown.minutes(null)).isEqualTo("-");
    }

    @Test
    void orderReportContainsTotalsAndTables() {
        OrderRow total = new OrderRow(null, 300, 250, new BigDecimal("50000.00"), 30);
        OrderStats byRegion = stats(OrderGroupBy.REGION, List.of(new OrderRow("EAST", 300, 250,
                new BigDecimal("50000.00"), 30)), total);
        OrderStats byCategory = stats(OrderGroupBy.CATEGORY, List.of(
                new OrderRow("ELECTRONICS", 200, 170, new BigDecimal("40000.00"), 20),
                new OrderRow("BOOKS", 100, 80, new BigDecimal("10000.00"), 10)), total);
        OrderStats trend = stats(OrderGroupBy.MONTH, List.of(new OrderRow("2026-10", 300, 250,
                new BigDecimal("50000.00"), 30)), total);

        String md = ReportMarkdown.orderReport(HEADER, byRegion, byCategory, trend);

        assertThat(md)
                .startsWith("# 订单经营报告（测试）\n")
                .contains("- 统计区间：2026-10-01 至 2026-10-31（Asia/Shanghai）")
                .contains("- 区域范围：华东（EAST）")
                .contains("- 生成时间：2026-11-05 09:30")
                .contains("| 订单数 | 300 |")
                .contains("| 成交金额（元） | 50,000.00 |")
                .contains("| 客单价（元） | 200.00 |")
                .contains("| 取消率 | 10.0% |")
                .contains("| 数码（ELECTRONICS） | 200 | 170 | 40,000.00 | 10.0% |")
                .contains("## 按月")
                .contains("| 2026-10 | 300 |");
    }

    @Test
    void anomalyReportShowsSeverityLabelsAndHandlesEmptyTables() {
        AnomalyRow total = new AnomalyRow(null, 40, 6, 95.25);
        AnomalyStats byType = anomalies(AnomalyGroupBy.EVENT_TYPE,
                List.of(new AnomalyRow("API_ERROR", 40, 6, 95.25)), total);
        AnomalyStats bySeverity = anomalies(AnomalyGroupBy.SEVERITY,
                List.of(new AnomalyRow("4", 40, 6, 95.25)), total);
        AnomalyStats empty = anomalies(AnomalyGroupBy.REGION, List.of(), total);

        String md = ReportMarkdown.anomalyReport(HEADER, byType, bySeverity, empty, empty);

        assertThat(md)
                .contains("| 异常事件数 | 40 |")
                .contains("| 未处理占比 | 15.0% |")
                .contains("| 平均处理时长（分钟） | 95.3 |")
                .contains("| 接口错误（API_ERROR） | 40 | 6 | 95.3 |")
                .contains("| 4（严重） | 40 | 6 | 95.3 |")
                .contains("## 按区域\n\n无数据。");
    }

    private static OrderStats stats(OrderGroupBy groupBy, List<OrderRow> rows, OrderRow total) {
        return new OrderStats(FROM, TO, EAST, groupBy, rows, total, false);
    }

    private static AnomalyStats anomalies(AnomalyGroupBy groupBy, List<AnomalyRow> rows, AnomalyRow total) {
        return new AnomalyStats(FROM, TO, EAST, null, 1, groupBy, rows, total, false);
    }
}
