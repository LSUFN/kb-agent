package com.example.kbagent.report.domain;

/** 报告类型。内容由 Java 根据统计查询结果确定性生成，不让模型写报告正文（ADR-10）。 */
public enum ReportType {
    ORDER_SUMMARY("订单经营报告"),
    ANOMALY_SUMMARY("异常事件报告");

    private final String displayName;

    ReportType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
