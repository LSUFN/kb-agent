package com.example.kbagent.observability.web;

import java.time.LocalDate;

import com.example.kbagent.common.api.ApiResponse;
import com.example.kbagent.observability.MetricsQueryService;
import com.example.kbagent.observability.MetricsQueryService.MetricsOverview;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 统计概览（§6.5），只对 ADMIN 开放。 */
@RestController
@RequestMapping("/api/v1/admin/metrics")
@PreAuthorize("hasRole('ADMIN')")
public class AdminMetricsController {

    private final MetricsQueryService metrics;

    public AdminMetricsController(MetricsQueryService metrics) {
        this.metrics = metrics;
    }

    /**
     * 按业务时区的自然日聚合；from 包含、to 不包含，格式 yyyy-MM-dd。都不传时返回最近 7 天（含今天）。
     */
    @GetMapping("/overview")
    public ApiResponse<MetricsOverview> overview(@RequestParam(required = false) LocalDate from,
                                                 @RequestParam(required = false) LocalDate to) {
        return ApiResponse.ok(metrics.overview(from, to));
    }
}
