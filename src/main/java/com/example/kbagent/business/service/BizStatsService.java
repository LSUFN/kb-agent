package com.example.kbagent.business.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import com.example.kbagent.business.BusinessProperties;
import com.example.kbagent.business.repository.BizStatsRepository;
import com.example.kbagent.business.repository.BizStatsRepository.AnomalyRow;
import com.example.kbagent.business.repository.BizStatsRepository.OrderRow;
import com.example.kbagent.business.repository.BizStatsRepository.Window;
import com.example.kbagent.business.service.BizStats.AnomalyQuery;
import com.example.kbagent.business.service.BizStats.AnomalyStats;
import com.example.kbagent.business.service.BizStats.OrderQuery;
import com.example.kbagent.business.service.BizStats.OrderStats;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.domain.Region;
import com.example.kbagent.user.domain.Role;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单和异常事件统计。方法显式接收 Actor，并在这里再做一次角色和区域范围校验（ADR-07）：
 * 工具层的校验之外再加一道，不依赖调用方，也不依赖 ThreadLocal 中的 SecurityContext。
 */
@Service
@EnableConfigurationProperties(BusinessProperties.class)
public class BizStatsService {

    private final BizStatsRepository repository;
    private final BusinessProperties properties;

    public BizStatsService(BizStatsRepository repository, BusinessProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public OrderStats orderStats(Actor actor, OrderQuery query) {
        requireAnalyst(actor);
        validateRange(query.from(), query.to());
        Set<Region> regions = DataScope.narrow(actor, query.region());
        Window window = window(query.from(), query.to(), regions);
        List<OrderRow> rows = repository.orderStats(window, query.groupBy(), properties.maxRows() + 1);
        boolean truncated = rows.size() > properties.maxRows();
        return new OrderStats(query.from(), query.to(), regions, query.groupBy(),
                truncated ? List.copyOf(rows.subList(0, properties.maxRows())) : rows,
                repository.orderTotals(window), truncated);
    }

    @Transactional(readOnly = true)
    public AnomalyStats anomalyStats(Actor actor, AnomalyQuery query) {
        requireAnalyst(actor);
        validateRange(query.from(), query.to());
        int minSeverity = query.minSeverity() == null ? 1 : query.minSeverity();
        if (minSeverity < 1 || minSeverity > 4) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "minSeverity 的取值范围是 1～4");
        }
        Set<Region> regions = DataScope.narrow(actor, query.region());
        Window window = window(query.from(), query.to(), regions);
        List<AnomalyRow> rows = repository.anomalyStats(window, query.eventType(), minSeverity, query.groupBy(),
                properties.maxRows() + 1);
        boolean truncated = rows.size() > properties.maxRows();
        return new AnomalyStats(query.from(), query.to(), regions, query.eventType(), minSeverity, query.groupBy(),
                truncated ? List.copyOf(rows.subList(0, properties.maxRows())) : rows,
                repository.anomalyTotals(window, query.eventType(), minSeverity), truncated);
    }

    /**
     * 时间范围校验：两端都要给出，from 不晚于 to，跨度（含首尾）不超过上限。
     *
     * @return 不合法时的说明；合法时为 null
     */
    public String rangeViolation(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            return "from 和 to 都必须提供，格式为 yyyy-MM-dd";
        }
        if (from.isAfter(to)) {
            return "from 不能晚于 to";
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > properties.maxSpanDays()) {
            return "时间跨度不能超过 " + properties.maxSpanDays() + " 天";
        }
        return null;
    }

    private void validateRange(LocalDate from, LocalDate to) {
        String violation = rangeViolation(from, to);
        if (violation != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, violation);
        }
    }

    private Window window(LocalDate from, LocalDate to, Set<Region> regions) {
        return new Window(from.atStartOfDay(properties.zone()).toOffsetDateTime(),
                to.plusDays(1).atStartOfDay(properties.zone()).toOffsetDateTime(), regions, properties.zone());
    }

    private static void requireAnalyst(Actor actor) {
        if (actor.role() != Role.ANALYST && actor.role() != Role.ADMIN) {
            throw new DataScope.ScopeDeniedException("当前账号无权查看业务数据");
        }
    }
}
