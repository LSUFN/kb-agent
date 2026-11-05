package com.example.kbagent.report.web;

import static com.example.kbagent.support.TestActors.ANALYST;
import static com.example.kbagent.support.TestActors.as;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.report.domain.ReportType;
import com.example.kbagent.report.service.ReportService;
import com.example.kbagent.report.service.ReportService.ReportDetail;
import com.example.kbagent.report.service.ReportService.ReportView;
import com.example.kbagent.support.SecuredWebMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SecuredWebMvcTest(ReportController.class)
class ReportControllerTest {

    private static final ReportView VIEW = new ReportView(7L, ANALYST.userId(), 5L, ReportType.ORDER_SUMMARY,
            "订单经营报告（2026-10-01 至 2026-10-31，EAST）", "{}", Instant.now());

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ReportService reports;

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(get("/api/v1/reports")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reports);
    }

    @Test
    void listAndDetail() throws Exception {
        when(reports.list(ANALYST, 1, 20)).thenReturn(new PageResponse<>(List.of(VIEW), 1, 20, 1));
        when(reports.get(ANALYST, 7L)).thenReturn(new ReportDetail(VIEW, "# 订单经营报告"));

        mvc.perform(get("/api/v1/reports").with(as(ANALYST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].type").value("ORDER_SUMMARY"));
        mvc.perform(get("/api/v1/reports/7").with(as(ANALYST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentMd").value("# 订单经营报告"));
    }

    @Test
    void downloadIsMarkdownWithAnEncodedFilename() throws Exception {
        when(reports.get(ANALYST, 7L)).thenReturn(new ReportDetail(VIEW, "# 订单经营报告\n"));

        byte[] body = mvc.perform(get("/api/v1/reports/7/download").with(as(ANALYST)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/markdown;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("filename*=UTF-8''")))
                .andReturn().getResponse().getContentAsByteArray();
        org.assertj.core.api.Assertions.assertThat(new String(body, StandardCharsets.UTF_8))
                .isEqualTo("# 订单经营报告\n");
    }

    @Test
    void someoneElsesReportIs404() throws Exception {
        when(reports.get(ANALYST, 8L)).thenThrow(BusinessException.notFound("报告"));

        mvc.perform(get("/api/v1/reports/8").with(as(ANALYST))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/reports/8/download").with(as(ANALYST))).andExpect(status().isNotFound());
    }
}
