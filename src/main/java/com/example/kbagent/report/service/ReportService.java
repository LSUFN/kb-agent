package com.example.kbagent.report.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.example.kbagent.business.BusinessProperties;
import com.example.kbagent.business.domain.AnomalyGroupBy;
import com.example.kbagent.business.domain.OrderGroupBy;
import com.example.kbagent.business.repository.BizStatsRepository.AnomalyRow;
import com.example.kbagent.business.repository.BizStatsRepository.OrderRow;
import com.example.kbagent.business.service.BizStats.AnomalyQuery;
import com.example.kbagent.business.service.BizStats.AnomalyStats;
import com.example.kbagent.business.service.BizStats.OrderQuery;
import com.example.kbagent.business.service.BizStats.OrderStats;
import com.example.kbagent.business.service.BizStatsService;
import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.RateLimitedException;
import com.example.kbagent.common.ratelimit.RateLimiter;
import com.example.kbagent.report.domain.Report;
import com.example.kbagent.report.domain.ReportType;
import com.example.kbagent.report.repository.ReportRepository;
import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.domain.Region;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 报告的生成与查看（§6.5）。生成只通过 Agent 工具触发；报告归生成人所有，ADMIN 可以查看所有人的报告，
 * 其他人访问不属于自己的报告返回 404。
 */
@Service
public class ReportService {

    /** 生成结果。highlights 是给模型看的几个关键数字，完整内容在报告里。 */
    public record GeneratedReport(long id, String title, ReportType type, LocalDate from, LocalDate to,
                                  Set<Region> regions, Map<String, Object> highlights) {
    }

    public record ReportView(long id, long userId, Long conversationId, ReportType type, String title,
                             String params, Instant createdAt) {

        static ReportView of(Report r) {
            return new ReportView(r.id(), r.userId(), r.conversationId(), r.reportType(), r.title(), r.params(),
                    r.createdAt());
        }
    }

    public record ReportDetail(ReportView report, String contentMd) {
    }

    /** 报告趋势部分在这个天数以内按天分组，否则按月。 */
    private static final int DAILY_TREND_MAX_DAYS = 31;

    private final BizStatsService stats;
    private final ReportRepository reports;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final BusinessProperties businessProperties;
    private final Clock clock;
    private final int ratePerMinute;

    public ReportService(BizStatsService stats, ReportRepository reports, RateLimiter rateLimiter,
                         ObjectMapper objectMapper, BusinessProperties businessProperties, Clock clock,
                         @Value("${app.agent.report-rate-limit-per-minute:5}") int ratePerMinute) {
        this.stats = stats;
        this.reports = reports;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.businessProperties = businessProperties;
        this.clock = clock;
        this.ratePerMinute = ratePerMinute;
    }

    /**
     * 生成报告并保存。区域范围由 BizStatsService 按 Actor 收敛，报告里记录的是收敛之后的范围。
     *
     * @param region 为 null 时使用用户的全部区域范围
     */
    @Transactional
    public GeneratedReport generate(Actor actor, ReportType type, LocalDate from, LocalDate to, Region region,
                                    Long conversationId) {
        RateLimiter.Decision decision = rateLimiter.tryAcquire("report:" + actor.userId(), ratePerMinute,
                Duration.ofMinutes(1));
        if (!decision.allowed()) {
            throw new RateLimitedException(decision.retryAfter());
        }
        boolean daily = ChronoUnit.DAYS.between(from, to) + 1 <= DAILY_TREND_MAX_DAYS;
        return switch (type) {
            case ORDER_SUMMARY -> orderReport(actor, from, to, region, conversationId, daily);
            case ANOMALY_SUMMARY -> anomalyReport(actor, from, to, region, conversationId, daily);
        };
    }

    private GeneratedReport orderReport(Actor actor, LocalDate from, LocalDate to, Region region,
                                        Long conversationId, boolean daily) {
        OrderStats byRegion = stats.orderStats(actor, new OrderQuery(from, to, region, OrderGroupBy.REGION));
        OrderStats byCategory = stats.orderStats(actor, new OrderQuery(from, to, region, OrderGroupBy.CATEGORY));
        OrderStats trend = stats.orderStats(actor,
                new OrderQuery(from, to, region, daily ? OrderGroupBy.DAY : OrderGroupBy.MONTH));
        ReportMarkdown.Header header = header(actor, ReportType.ORDER_SUMMARY, from, to, byRegion.regions());
        String markdown = ReportMarkdown.orderReport(header, byRegion, byCategory, trend);
        OrderRow total = byRegion.total();
        Map<String, Object> highlights = new LinkedHashMap<>();
        highlights.put("orderCount", total.orderCount());
        highlights.put("paidOrderCount", total.paidOrderCount());
        highlights.put("paidAmount", total.paidAmount());
        highlights.put("cancelRate", ReportMarkdown.rate(total.cancelledCount(), total.orderCount()));
        return save(actor, conversationId, ReportType.ORDER_SUMMARY, header, byRegion.regions(), region, markdown,
                highlights);
    }

    private GeneratedReport anomalyReport(Actor actor, LocalDate from, LocalDate to, Region region,
                                          Long conversationId, boolean daily) {
        AnomalyStats byType = stats.anomalyStats(actor, anomalyQuery(from, to, region, AnomalyGroupBy.EVENT_TYPE));
        AnomalyStats bySeverity = stats.anomalyStats(actor, anomalyQuery(from, to, region, AnomalyGroupBy.SEVERITY));
        AnomalyStats byRegion = stats.anomalyStats(actor, anomalyQuery(from, to, region, AnomalyGroupBy.REGION));
        AnomalyStats trend = stats.anomalyStats(actor,
                anomalyQuery(from, to, region, daily ? AnomalyGroupBy.DAY : AnomalyGroupBy.MONTH));
        ReportMarkdown.Header header = header(actor, ReportType.ANOMALY_SUMMARY, from, to, byType.regions());
        String markdown = ReportMarkdown.anomalyReport(header, byType, bySeverity, byRegion, trend);
        AnomalyRow total = byType.total();
        Map<String, Object> highlights = new LinkedHashMap<>();
        highlights.put("eventCount", total.eventCount());
        highlights.put("unresolvedCount", total.unresolvedCount());
        highlights.put("avgResolveMinutes", ReportMarkdown.minutes(total.avgResolveMinutes()));
        return save(actor, conversationId, ReportType.ANOMALY_SUMMARY, header, byType.regions(), region, markdown,
                highlights);
    }

    private static AnomalyQuery anomalyQuery(LocalDate from, LocalDate to, Region region, AnomalyGroupBy groupBy) {
        return new AnomalyQuery(from, to, null, null, region, groupBy);
    }

    private ReportMarkdown.Header header(Actor actor, ReportType type, LocalDate from, LocalDate to,
                                         Set<Region> regions) {
        String title = type.displayName() + "（" + from + " 至 " + to + "，"
                + String.join("、", regions.stream().map(Region::name).toList()) + "）";
        return new ReportMarkdown.Header(title, from, to, regions, businessProperties.zone().getId(),
                clock.instant().atZone(businessProperties.zone()), actor.displayName());
    }

    private GeneratedReport save(Actor actor, Long conversationId, ReportType type, ReportMarkdown.Header header,
                                 Set<Region> regions, Region requestedRegion, String markdown,
                                 Map<String, Object> highlights) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", header.from().toString());
        params.put("to", header.to().toString());
        params.put("requestedRegion", requestedRegion == null ? null : requestedRegion.name());
        params.put("regions", regions.stream().map(Region::name).toList());
        Report saved = reports.save(Report.create(actor.userId(), conversationId, type, header.title(),
                objectMapper.writeValueAsString(params), markdown));
        return new GeneratedReport(saved.id(), saved.title(), type, header.from(), header.to(), regions, highlights);
    }

    /** 本人的报告；ADMIN 看到所有人的报告。按生成时间倒序，@param page 从 1 开始 */
    @Transactional(readOnly = true)
    public PageResponse<ReportView> list(Actor actor, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Order.desc("id")));
        return PageResponse.of(actor.isAdmin() ? reports.findAll(pageable)
                : reports.findByUserId(actor.userId(), pageable), ReportView::of);
    }

    @Transactional(readOnly = true)
    public ReportDetail get(Actor actor, long reportId) {
        Report report = visible(actor, reportId);
        return new ReportDetail(ReportView.of(report), report.contentMd());
    }

    private Report visible(Actor actor, long reportId) {
        return reports.findById(reportId)
                .filter(r -> actor.isAdmin() || r.userId() == actor.userId())
                .orElseThrow(() -> BusinessException.notFound("报告"));
    }
}
