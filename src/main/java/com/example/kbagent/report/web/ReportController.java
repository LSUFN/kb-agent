package com.example.kbagent.report.web;

import java.nio.charset.StandardCharsets;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.example.kbagent.common.api.ApiResponse;
import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.report.service.ReportService;
import com.example.kbagent.report.service.ReportService.ReportDetail;
import com.example.kbagent.report.service.ReportService.ReportView;
import com.example.kbagent.user.domain.Actor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 报告的查看与下载（§6.5）：本人或 ADMIN。报告只能在 Agent 对话中生成。 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private static final MediaType MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    public ApiResponse<PageResponse<ReportView>> list(@AuthenticationPrincipal Actor actor,
                                                      @RequestParam(defaultValue = "1") @Min(1) int page,
                                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(reports.list(actor, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<ReportDetail> get(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
        return ApiResponse.ok(reports.get(actor, id));
    }

    /** 下载 Markdown 原文。文件名包含中文标题，按 RFC 6266 用 filename* 编码。 */
    @GetMapping("/{id}/download")
    public ResponseEntity<String> download(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
        ReportDetail detail = reports.get(actor, id);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(detail.report().title() + ".md", StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MARKDOWN)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(detail.contentMd());
    }
}
