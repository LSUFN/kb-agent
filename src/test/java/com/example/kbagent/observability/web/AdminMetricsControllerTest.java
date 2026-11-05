package com.example.kbagent.observability.web;

import static com.example.kbagent.support.TestActors.ADMIN;
import static com.example.kbagent.support.TestActors.ANALYST;
import static com.example.kbagent.support.TestActors.USER;
import static com.example.kbagent.support.TestActors.as;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import com.example.kbagent.observability.MetricsQueryService;
import com.example.kbagent.observability.MetricsQueryService.MetricsDay;
import com.example.kbagent.observability.MetricsQueryService.MetricsOverview;
import com.example.kbagent.support.SecuredWebMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SecuredWebMvcTest(AdminMetricsController.class)
class AdminMetricsControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    MetricsQueryService metrics;

    @Test
    void onlyAdminCanReadMetrics() throws Exception {
        mvc.perform(get("/api/v1/admin/metrics/overview").with(as(USER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/metrics/overview").with(as(ANALYST))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/metrics/overview")).andExpect(status().isUnauthorized());
        verifyNoInteractions(metrics);
    }

    @Test
    void datesAreParsedAsBusinessDays() throws Exception {
        LocalDate from = LocalDate.parse("2026-11-01");
        LocalDate to = LocalDate.parse("2026-11-06");
        MetricsDay total = new MetricsDay(null, 12, 1, 3, 4200L, 9800L, 800L, 20000, 3000, 9, 2);
        when(metrics.overview(from, to)).thenReturn(new MetricsOverview(from, to, "Asia/Shanghai", total, List.of()));

        mvc.perform(get("/api/v1/admin/metrics/overview").with(as(ADMIN))
                        .param("from", "2026-11-01").param("to", "2026-11-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from").value("2026-11-01"))
                .andExpect(jsonPath("$.data.total.p95LatencyMs").value(9800))
                .andExpect(jsonPath("$.data.total.toolDenied").value(2));
        mvc.perform(get("/api/v1/admin/metrics/overview").with(as(ADMIN)).param("from", "11/01/2026"))
                .andExpect(status().isBadRequest());
    }
}
