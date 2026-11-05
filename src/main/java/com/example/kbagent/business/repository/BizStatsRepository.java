package com.example.kbagent.business.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;

import com.example.kbagent.business.domain.AnomalyGroupBy;
import com.example.kbagent.business.domain.AnomalyType;
import com.example.kbagent.business.domain.OrderGroupBy;
import com.example.kbagent.user.domain.Region;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 订单和异常事件的只读统计（§3.3 business）。只有固定的参数化 SQL：
 * 分组表达式来自枚举常量，可选条件是常量片段，所有取值都通过参数绑定。模型能影响的只有枚举和日期。
 */
@Repository
public class BizStatsRepository {

    private static final String ORDER_METRICS = """
            count(*) AS order_count,
            count(*) FILTER (WHERE status IN ('PAID', 'SHIPPED')) AS paid_order_count,
            coalesce(sum(amount) FILTER (WHERE status IN ('PAID', 'SHIPPED')), 0) AS paid_amount,
            count(*) FILTER (WHERE status IN ('CANCELLED', 'REFUNDED')) AS cancelled_count""";

    private static final String ORDER_WINDOW = "created_at >= :from AND created_at < :to AND region IN (:regions)";

    private static final String ANOMALY_METRICS = """
            count(*) AS event_count,
            count(*) FILTER (WHERE resolved_at IS NULL) AS unresolved_count,
            avg(extract(epoch FROM resolved_at - occurred_at) / 60) FILTER (WHERE resolved_at IS NOT NULL)
                AS avg_resolve_minutes""";

    /** 统计的时间窗口：[from, to)，以及区域范围。 */
    public record Window(OffsetDateTime from, OffsetDateTime to, Collection<Region> regions, ZoneId zone) {
    }

    /** key 为 null 表示合计行。 */
    public record OrderRow(String key, long orderCount, long paidOrderCount, BigDecimal paidAmount,
                           long cancelledCount) {
    }

    /** avgResolveMinutes 为 null 表示这一组没有已处理的事件。 */
    public record AnomalyRow(String key, long eventCount, long unresolvedCount, Double avgResolveMinutes) {
    }

    private final JdbcClient jdbc;

    public BizStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<OrderRow> orderStats(Window window, OrderGroupBy groupBy, int limit) {
        String order = groupBy == OrderGroupBy.DAY || groupBy == OrderGroupBy.MONTH ? "1" : "2 DESC, 1";
        return orderQuery("SELECT " + groupBy.sqlExpression() + " AS k, " + ORDER_METRICS
                + " FROM biz_order WHERE " + ORDER_WINDOW + " GROUP BY 1 ORDER BY " + order + " LIMIT :limit", window)
                .param("limit", limit)
                .query((rs, n) -> orderRow(rs.getString("k"), rs))
                .list();
    }

    public OrderRow orderTotals(Window window) {
        return orderQuery("SELECT " + ORDER_METRICS + " FROM biz_order WHERE " + ORDER_WINDOW, window)
                .query((rs, n) -> orderRow(null, rs))
                .single();
    }

    public List<AnomalyRow> anomalyStats(Window window, AnomalyType eventType, int minSeverity,
                                         AnomalyGroupBy groupBy, int limit) {
        String order = groupBy == AnomalyGroupBy.DAY || groupBy == AnomalyGroupBy.MONTH
                || groupBy == AnomalyGroupBy.SEVERITY ? "1" : "2 DESC, 1";
        return anomalyQuery("SELECT " + groupBy.sqlExpression() + " AS k, " + ANOMALY_METRICS
                + " FROM biz_anomaly_event WHERE " + anomalyWindow(eventType)
                + " GROUP BY 1 ORDER BY " + order + " LIMIT :limit", window, eventType, minSeverity)
                .param("limit", limit)
                .query((rs, n) -> anomalyRow(rs.getString("k"), rs))
                .list();
    }

    public AnomalyRow anomalyTotals(Window window, AnomalyType eventType, int minSeverity) {
        return anomalyQuery("SELECT " + ANOMALY_METRICS + " FROM biz_anomaly_event WHERE " + anomalyWindow(eventType),
                window, eventType, minSeverity)
                .query((rs, n) -> anomalyRow(null, rs))
                .single();
    }

    private static String anomalyWindow(AnomalyType eventType) {
        return "occurred_at >= :from AND occurred_at < :to AND region IN (:regions) AND severity >= :minSeverity"
                + (eventType == null ? "" : " AND event_type = :eventType");
    }

    private JdbcClient.StatementSpec orderQuery(String sql, Window window) {
        return bindWindow(jdbc.sql(sql), window);
    }

    private JdbcClient.StatementSpec anomalyQuery(String sql, Window window, AnomalyType eventType, int minSeverity) {
        JdbcClient.StatementSpec spec = bindWindow(jdbc.sql(sql), window).param("minSeverity", minSeverity);
        return eventType == null ? spec : spec.param("eventType", eventType.name());
    }

    private static JdbcClient.StatementSpec bindWindow(JdbcClient.StatementSpec spec, Window window) {
        // :zone 只在按天、按月分组时出现；多余的命名参数会被忽略
        return spec.param("from", window.from())
                .param("to", window.to())
                .param("regions", window.regions().stream().map(Region::name).toList())
                .param("zone", window.zone().getId());
    }

    private static OrderRow orderRow(String key, ResultSet rs) throws SQLException {
        return new OrderRow(key, rs.getLong("order_count"), rs.getLong("paid_order_count"),
                rs.getBigDecimal("paid_amount"), rs.getLong("cancelled_count"));
    }

    private static AnomalyRow anomalyRow(String key, ResultSet rs) throws SQLException {
        BigDecimal avg = rs.getBigDecimal("avg_resolve_minutes");
        return new AnomalyRow(key, rs.getLong("event_count"), rs.getLong("unresolved_count"),
                avg == null ? null : avg.doubleValue());
    }
}
