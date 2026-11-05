package com.example.kbagent.tool;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.NotNull;

import com.example.kbagent.business.domain.OrderGroupBy;
import com.example.kbagent.business.repository.BizStatsRepository.OrderRow;
import com.example.kbagent.business.service.BizStats.OrderQuery;
import com.example.kbagent.business.service.BizStats.OrderStats;
import com.example.kbagent.business.service.BizStatsService;
import com.example.kbagent.business.service.DataScope;
import com.example.kbagent.user.domain.Region;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** queryOrderStats：订单统计（ANALYST 限本人区域，ADMIN 全部区域）。 */
@Component
public class OrderStatsTool implements AgentTool<OrderStatsTool.Args> {

    public record Args(
            @ToolParam(description = "开始日期（包含），格式 yyyy-MM-dd") @NotNull LocalDate from,
            @ToolParam(description = "结束日期（包含），格式 yyyy-MM-dd；与 from 的跨度不超过 366 天") @NotNull LocalDate to,
            @ToolParam(required = false, description = "只统计这个区域；不传则统计当前用户有权查看的全部区域")
            Region region,
            @ToolParam(description = "分组维度：REGION 按区域，CATEGORY 按品类，STATUS 按订单状态，DAY 按天，MONTH 按月")
            @NotNull OrderGroupBy groupBy) {
    }

    private final BizStatsService stats;

    public OrderStatsTool(BizStatsService stats) {
        this.stats = stats;
    }

    @Override
    public String name() {
        return ToolPolicy.QUERY_ORDER_STATS;
    }

    @Override
    public String description() {
        return "统计订单数据：订单数、成交订单数（已支付和已发货）、成交金额（元）、取消和退款订单数，可按区域、品类、状态、"
                + "天或月分组，同时返回全部数据的合计。回答中的订单数字必须来自这个工具的结果。";
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
        OrderStats result = stats.orderStats(ctx.actor(),
                new OrderQuery(args.from(), args.to(), args.region(), args.groupBy()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", result.from().toString());
        data.put("to", result.to().toString());
        data.put("regions", result.regions());
        data.put("groupBy", result.groupBy());
        data.put("rows", result.rows());
        data.put("total", result.total());
        data.put("truncated", result.truncated());
        data.put("note", "paidAmount 是已支付和已发货订单的金额合计（元）；cancelledCount 是取消和退款的订单数；"
                + "rows 中 key 为分组值；" + ToolNotes.REGIONS);
        OrderRow total = result.total();
        return new Output(data, "共 " + result.rows().size() + " 组，订单 " + total.orderCount() + " 单，成交金额 "
                + total.paidAmount().toPlainString() + " 元" + (result.truncated() ? "（分组已截断）" : ""));
    }
}
