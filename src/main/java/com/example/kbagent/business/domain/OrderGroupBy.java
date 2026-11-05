package com.example.kbagent.business.domain;

/**
 * 订单统计的分组维度。SQL 表达式是常量，模型只能在这几个枚举值中选择，不能影响 SQL 结构。
 * DAY / MONTH 按业务时区（app.business.zone）取日期，时区作为参数绑定。
 */
public enum OrderGroupBy {
    REGION("region"),
    CATEGORY("category"),
    STATUS("status"),
    DAY("to_char(created_at AT TIME ZONE :zone, 'YYYY-MM-DD')"),
    MONTH("to_char(created_at AT TIME ZONE :zone, 'YYYY-MM')");

    private final String sqlExpression;

    OrderGroupBy(String sqlExpression) {
        this.sqlExpression = sqlExpression;
    }

    public String sqlExpression() {
        return sqlExpression;
    }
}
