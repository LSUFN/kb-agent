package com.example.kbagent.tool;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.NotNull;

import com.example.kbagent.business.service.BizStatsService;
import com.example.kbagent.business.service.DataScope;
import com.example.kbagent.report.domain.ReportType;
import com.example.kbagent.report.service.ReportService;
import com.example.kbagent.report.service.ReportService.GeneratedReport;
import com.example.kbagent.user.domain.Region;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * generateReport：生成 Markdown 报告并保存（ANALYST 限本人区域，报告归本人；ADMIN 全部区域）。
 * 这是工具中唯一的写操作；报告里的数字由 Java 根据 SQL 结果生成，模型只拿到几个关键数字（ADR-10）。
 */
@Component
public class ReportTool implements AgentTool<ReportTool.Args> {

    public record Args(
            @ToolParam(description = "报告类型：ORDER_SUMMARY 订单经营报告，ANOMALY_SUMMARY 异常事件报告")
            @NotNull ReportType reportType,
            @ToolParam(description = "开始日期（包含），格式 yyyy-MM-dd") @NotNull LocalDate from,
            @ToolParam(description = "结束日期（包含），格式 yyyy-MM-dd；与 from 的跨度不超过 366 天") @NotNull LocalDate to,
            @ToolParam(required = false, description = "只统计这个区域；不传则使用当前用户有权查看的全部区域")
            Region region) {
    }

    private final ReportService reports;
    private final BizStatsService stats;

    public ReportTool(ReportService reports, BizStatsService stats) {
        this.reports = reports;
        this.stats = stats;
    }

    @Override
    public String name() {
        return ToolPolicy.GENERATE_REPORT;
    }

    @Override
    public String description() {
        return "生成一份 Markdown 格式的统计报告并保存，用户可以在报告列表中查看和下载。只在用户明确要求生成报告时使用；"
                + "每个用户每分钟最多生成 5 份。";
    }

    @Override
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String validate(Args args) {
        return stats.rangeViolation(args.from(), args.to());
    }

    @Override
    public Set<Region> narrow(Args args, AgentRequestContext ctx) {
        return DataScope.narrow(ctx.actor(), args.region());
    }

    @Override
    public Output execute(Args args, Set<Region> scope, AgentRequestContext ctx) {
        GeneratedReport report = reports.generate(ctx.actor(), args.reportType(), args.from(), args.to(),
                args.region(), ctx.conversationId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reportId", report.id());
        data.put("title", report.title());
        data.put("regions", report.regions());
        data.put("highlights", report.highlights());
        data.put("downloadUrl", "/api/v1/reports/" + report.id() + "/download");
        data.put("message", "报告已生成，用户可以在报告列表中查看和下载");
        return new Output(data, "已生成报告 #" + report.id() + "：" + report.title());
    }
}
