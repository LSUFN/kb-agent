package com.example.kbagent.report.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.kbagent.business.repository.BizStatsRepository.AnomalyRow;
import com.example.kbagent.business.repository.BizStatsRepository.OrderRow;
import com.example.kbagent.business.service.BizStats.AnomalyStats;
import com.example.kbagent.business.service.BizStats.OrderStats;
import com.example.kbagent.user.domain.Region;

/**
 * 把统计结果渲染成 Markdown 报告。纯函数：报告里的每个数字都来自传入的统计结果（ADR-10），
 * 集成测试会把这些数字与直接执行 SQL 的结果比较。
 */
public final class ReportMarkdown {

    static final Map<String, String> REGION_NAMES = Map.of(
            "EAST", "华东", "NORTH", "华北", "SOUTH", "华南", "WEST", "西部", "CENTRAL", "华中");
    static final Map<String, String> CATEGORY_NAMES = Map.of(
            "ELECTRONICS", "数码", "APPLIANCE", "家电", "APPAREL", "服饰", "FOOD", "食品", "BOOKS", "图书");
    static final Map<String, String> EVENT_TYPE_NAMES = Map.of(
            "PAYMENT_TIMEOUT", "支付超时", "INVENTORY_MISMATCH", "库存不一致", "API_ERROR", "接口错误",
            "LOGISTICS_DELAY", "物流延迟");
    static final Map<String, String> SEVERITY_NAMES = Map.of("1", "1（低）", "2", "2（中）", "3", "3（高）", "4", "4（严重）");

    private static final DateTimeFormatter GENERATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 报告页眉需要的信息。 */
    public record Header(String title, LocalDate from, LocalDate to, Set<Region> regions, String zone,
                         ZonedDateTime generatedAt, String author) {
    }

    private ReportMarkdown() {
    }

    public static String orderReport(Header header, OrderStats byRegion, OrderStats byCategory, OrderStats trend) {
        StringBuilder md = header(header, "本报告中的数字全部由系统直接统计订单数据生成。");
        OrderRow total = byRegion.total();
        md.append("## 总览\n\n| 指标 | 数值 |\n|---|---|\n")
                .append("| 订单数 | ").append(count(total.orderCount())).append(" |\n")
                .append("| 成交订单数（已支付、已发货） | ").append(count(total.paidOrderCount())).append(" |\n")
                .append("| 成交金额（元） | ").append(amount(total.paidAmount())).append(" |\n")
                .append("| 客单价（元） | ").append(amount(average(total.paidAmount(), total.paidOrderCount())))
                .append(" |\n")
                .append("| 取消和退款订单数 | ").append(count(total.cancelledCount())).append(" |\n")
                .append("| 取消率 | ").append(rate(total.cancelledCount(), total.orderCount())).append(" |\n\n");
        orderTable(md, "按区域", "区域", byRegion.rows(), REGION_NAMES);
        orderTable(md, "按品类", "品类", byCategory.rows(), CATEGORY_NAMES);
        orderTable(md, trend.groupBy().name().equals("DAY") ? "按天" : "按月", "日期", trend.rows(), Map.of());
        return md.toString();
    }

    public static String anomalyReport(Header header, AnomalyStats byType, AnomalyStats bySeverity,
                                       AnomalyStats byRegion, AnomalyStats trend) {
        StringBuilder md = header(header, "本报告中的数字全部由系统直接统计异常事件数据生成。");
        AnomalyRow total = byType.total();
        md.append("## 总览\n\n| 指标 | 数值 |\n|---|---|\n")
                .append("| 异常事件数 | ").append(count(total.eventCount())).append(" |\n")
                .append("| 未处理事件数 | ").append(count(total.unresolvedCount())).append(" |\n")
                .append("| 未处理占比 | ").append(rate(total.unresolvedCount(), total.eventCount())).append(" |\n")
                .append("| 平均处理时长（分钟） | ").append(minutes(total.avgResolveMinutes())).append(" |\n\n");
        anomalyTable(md, "按类型", "类型", byType.rows(), EVENT_TYPE_NAMES);
        anomalyTable(md, "按严重程度", "严重程度", bySeverity.rows(), SEVERITY_NAMES);
        anomalyTable(md, "按区域", "区域", byRegion.rows(), REGION_NAMES);
        anomalyTable(md, trend.groupBy().name().equals("DAY") ? "按天" : "按月", "日期", trend.rows(), Map.of());
        return md.toString();
    }

    private static StringBuilder header(Header h, String note) {
        return new StringBuilder("# ").append(h.title()).append("\n\n")
                .append("- 统计区间：").append(h.from()).append(" 至 ").append(h.to())
                .append("（").append(h.zone()).append("）\n")
                .append("- 区域范围：").append(regions(h.regions())).append('\n')
                .append("- 生成时间：").append(GENERATED_AT.format(h.generatedAt())).append('\n')
                .append("- 生成人：").append(h.author()).append("\n\n")
                .append("> ").append(note).append("\n\n");
    }

    private static void orderTable(StringBuilder md, String title, String keyHeader, List<OrderRow> rows,
                                   Map<String, String> names) {
        md.append("## ").append(title).append("\n\n");
        if (rows.isEmpty()) {
            md.append("无数据。\n\n");
            return;
        }
        md.append("| ").append(keyHeader).append(" | 订单数 | 成交订单数 | 成交金额（元） | 取消率 |\n|---|---|---|---|---|\n");
        for (OrderRow row : rows) {
            md.append("| ").append(label(row.key(), names)).append(" | ").append(count(row.orderCount()))
                    .append(" | ").append(count(row.paidOrderCount())).append(" | ").append(amount(row.paidAmount()))
                    .append(" | ").append(rate(row.cancelledCount(), row.orderCount())).append(" |\n");
        }
        md.append('\n');
    }

    private static void anomalyTable(StringBuilder md, String title, String keyHeader, List<AnomalyRow> rows,
                                     Map<String, String> names) {
        md.append("## ").append(title).append("\n\n");
        if (rows.isEmpty()) {
            md.append("无数据。\n\n");
            return;
        }
        md.append("| ").append(keyHeader).append(" | 事件数 | 未处理 | 平均处理时长（分钟） |\n|---|---|---|---|\n");
        for (AnomalyRow row : rows) {
            md.append("| ").append(label(row.key(), names)).append(" | ").append(count(row.eventCount()))
                    .append(" | ").append(count(row.unresolvedCount())).append(" | ")
                    .append(minutes(row.avgResolveMinutes())).append(" |\n");
        }
        md.append('\n');
    }

    static String regions(Set<Region> regions) {
        return regions.stream().map(r -> REGION_NAMES.get(r.name()) + "（" + r.name() + "）")
                .collect(Collectors.joining("、"));
    }

    private static String label(String key, Map<String, String> names) {
        String name = names.get(key);
        return name == null ? key : name + (names == SEVERITY_NAMES ? "" : "（" + key + "）");
    }

    public static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    public static String amount(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return format.format(value.setScale(2, RoundingMode.HALF_UP));
    }

    /** 百分比，保留一位小数；分母为 0 时显示“-”。 */
    public static String rate(long part, long whole) {
        return whole == 0 ? "-" : BigDecimal.valueOf(part * 100L)
                .divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    public static String minutes(Double value) {
        return value == null ? "-" : BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    static BigDecimal average(BigDecimal sum, long count) {
        return count == 0 ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }
}
