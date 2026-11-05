package com.example.kbagent.business.domain;

/** 异常事件统计的分组维度。与 {@link OrderGroupBy} 一样，SQL 表达式是常量。 */
public enum AnomalyGroupBy {
    EVENT_TYPE("event_type"),
    SEVERITY("severity::text"),
    REGION("region"),
    SOURCE_SYSTEM("source_system"),
    DAY("to_char(occurred_at AT TIME ZONE :zone, 'YYYY-MM-DD')"),
    MONTH("to_char(occurred_at AT TIME ZONE :zone, 'YYYY-MM')");

    private final String sqlExpression;

    AnomalyGroupBy(String sqlExpression) {
        this.sqlExpression = sqlExpression;
    }

    public String sqlExpression() {
        return sqlExpression;
    }
}
