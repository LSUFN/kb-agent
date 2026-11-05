package com.example.kbagent.business.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import com.example.kbagent.business.domain.AnomalyGroupBy;
import com.example.kbagent.business.domain.AnomalyType;
import com.example.kbagent.business.domain.OrderGroupBy;
import com.example.kbagent.business.repository.BizStatsRepository.AnomalyRow;
import com.example.kbagent.business.repository.BizStatsRepository.OrderRow;
import com.example.kbagent.user.domain.Region;

/** 业务统计的查询条件和结果。日期都是业务时区下的自然日，from 和 to 都包含在内。 */
public final class BizStats {

    private BizStats() {
    }

    /** @param region 为 null 时统计用户的全部区域范围 */
    public record OrderQuery(LocalDate from, LocalDate to, Region region, OrderGroupBy groupBy) {
    }

    /**
     * @param eventType   为 null 时统计全部类型
     * @param minSeverity 为 null 时从 1 开始
     */
    public record AnomalyQuery(LocalDate from, LocalDate to, AnomalyType eventType, Integer minSeverity,
                               Region region, AnomalyGroupBy groupBy) {
    }

    /**
     * @param regions   实际统计的区域（收敛之后）
     * @param total     全部数据的合计，不受行数上限影响
     * @param truncated 分组数超过上限，rows 只包含前面的部分
     */
    public record OrderStats(LocalDate from, LocalDate to, Set<Region> regions, OrderGroupBy groupBy,
                             List<OrderRow> rows, OrderRow total, boolean truncated) {
    }

    public record AnomalyStats(LocalDate from, LocalDate to, Set<Region> regions, AnomalyType eventType,
                               int minSeverity, AnomalyGroupBy groupBy, List<AnomalyRow> rows, AnomalyRow total,
                               boolean truncated) {
    }
}
