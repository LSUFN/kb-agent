package com.example.kbagent.observability.web;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import com.example.kbagent.common.api.ApiResponse;
import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.observability.CallLogQueryService;
import com.example.kbagent.observability.CallLogQueryService.LlmCallFilter;
import com.example.kbagent.observability.CallLogQueryService.LlmCallView;
import com.example.kbagent.observability.CallLogQueryService.ToolCallFilter;
import com.example.kbagent.observability.CallLogQueryService.ToolCallView;
import com.example.kbagent.observability.ToolAuditRecorder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 调用日志查询（§6.5），只对 ADMIN 开放；返回的都是写入时已脱敏的数据，没有任何接口能返回原文。 */
@RestController
@RequestMapping("/api/v1/admin/logs")
@PreAuthorize("hasRole('ADMIN')")
public class AdminLogController {

    private final CallLogQueryService logs;

    public AdminLogController(CallLogQueryService logs) {
        this.logs = logs;
    }

    /** @param from 包含；@param to 不包含；时间使用 ISO-8601，例如 2026-11-04T00:00:00Z */
    @GetMapping("/llm-calls")
    public ApiResponse<PageResponse<LlmCallView>> llmCalls(
            @RequestParam(required = false) @Size(max = 64) String traceId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Boolean success,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(logs.llmCalls(new LlmCallFilter(traceId, userId, success, from, to), page, size));
    }

    /** 工具调用审计：可按 decision（ALLOWED / DENIED / INVALID / BUDGET_EXCEEDED）过滤越权尝试。 */
    @GetMapping("/tool-calls")
    public ApiResponse<PageResponse<ToolCallView>> toolCalls(
            @RequestParam(required = false) @Size(max = 64) String traceId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @Size(max = 64) String toolName,
            @RequestParam(required = false) ToolAuditRecorder.Decision decision,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(logs.toolCalls(new ToolCallFilter(traceId, userId, toolName, decision, from, to),
                page, size));
    }
}
