package com.example.kbagent.business;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 业务统计配置（app.business.*，§4.4.3）。
 *
 * @param zone        日期按这个时区划分（“某一天”的起止时刻、按天/按月分组）
 * @param maxSpanDays 一次查询的最大时间跨度（含首尾两天）
 * @param maxRows     分组结果的最大行数，超出时截断并标记 truncated
 */
@ConfigurationProperties("app.business")
public record BusinessProperties(
        @DefaultValue("Asia/Shanghai") ZoneId zone,
        @DefaultValue("366") int maxSpanDays,
        @DefaultValue("100") int maxRows) {
}
