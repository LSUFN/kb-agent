package com.example.kbagent.tool;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import com.example.kbagent.business.domain.AnomalyGroupBy;
import com.example.kbagent.business.domain.AnomalyType;
import com.example.kbagent.business.repository.BizStatsRepository.AnomalyRow;
import com.example.kbagent.business.service.BizStats.AnomalyQuery;
import com.example.kbagent.business.service.BizStats.AnomalyStats;
import com.example.kbagent.business.service.BizStatsService;
import com.example.kbagent.business.service.DataScope;
import com.example.kbagent.user.domain.Region;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** countAnomalies：异常事件统计（ANALYST 限本人区域，ADMIN 全部区域）。 */
@Component
public class AnomalyStatsTool implements AgentTool<AnomalyStatsTool.Args> {

    public record Args(
            @ToolParam(description = "开始日期（包含），格式 yyyy-MM-dd") @NotNull LocalDate from,
            @ToolParam(description = "结束日期（包含），格式 yyyy-MM-dd；与 from 的跨度不超过 366 天") @NotNull LocalDate to,
            @ToolParam(required = false, description = "只统计这种类型：PAYMENT_TIMEOUT 支付超时，"
                    + "INVENTORY_MISMATCH 库存不一致，API_ERROR 接口错误，LOGISTICS_DELAY 物流延迟")
            AnomalyType eventType,
            @ToolParam(required = false, description = "最低严重程度 1～4（4 最严重），不传则从 1 开始")
            @Min(1) @Max(4) Integer minSeverity,
            @ToolParam(required = false, description = "只统计这个区域；不传则统计当前用户有权查看的全部区域")
            Region region,
            @ToolParam(description = "分组维度：EVENT_TYPE 按类型，SEVERITY 按严重程度，REGION 按区域，"
                    + "SOURCE_SYSTEM 按来源系统，DAY 按天，MONTH 按月")
            @NotNull AnomalyGroupBy groupBy) {
    }

    private final BizStatsService stats;

    public AnomalyStatsTool(BizStatsService stats) {
        this.stats = stats;
    }

    @Override
    public String name() {
        return ToolPolicy.COUNT_ANOMALIES;
    }

    @Override
    public String description() {
        return "统计业务异常事件：事件数、未处理数、已处理事件的平均处理时长（分钟），可按类型、严重程度、区域、来源系统、"
                + "天或月分组，同时返回全部数据的合计。回答中的异常事件数字必须来自这个工具的结果。";
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
        AnomalyStats result = stats.anomalyStats(ctx.actor(), new AnomalyQuery(args.from(), args.to(),
                args.eventType(), args.minSeverity(), args.region(), args.groupBy()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", result.from().toString());
        data.put("to", result.to().toString());
        data.put("regions", result.regions());
        data.put("eventType", result.eventType());
        data.put("minSeverity", result.minSeverity());
        data.put("groupBy", result.groupBy());
        data.put("rows", result.rows());
        data.put("total", result.total());
        data.put("truncated", result.truncated());
        data.put("note", "unresolvedCount 是尚未处理的事件数；avgResolveMinutes 是已处理事件的平均处理时长（分钟），"
                + "为 null 表示没有已处理的事件；rows 中 key 为分组值；" + ToolNotes.REGIONS);
        AnomalyRow total = result.total();
        return new Output(data, "共 " + total.eventCount() + " 条，分 " + result.rows().size() + " 组，未处理 "
                + total.unresolvedCount() + " 条" + (result.truncated() ? "（分组已截断）" : ""));
    }
}
