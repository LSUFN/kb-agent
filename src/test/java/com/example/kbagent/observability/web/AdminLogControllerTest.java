package com.example.kbagent.observability.web;

import static com.example.kbagent.support.TestActors.ADMIN;
import static com.example.kbagent.support.TestActors.ANALYST;
import static com.example.kbagent.support.TestActors.USER;
import static com.example.kbagent.support.TestActors.as;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.observability.CallLogQueryService;
import com.example.kbagent.observability.CallLogQueryService.LlmCallFilter;
import com.example.kbagent.observability.CallLogQueryService.ToolCallFilter;
import com.example.kbagent.observability.ToolAuditRecorder.Decision;
import com.example.kbagent.support.SecuredWebMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SecuredWebMvcTest(AdminLogController.class)
class AdminLogControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CallLogQueryService logs;

    @Test
    void onlyAdminCanReadCallLogs() throws Exception {
        mvc.perform(get("/api/v1/admin/logs/llm-calls").with(as(USER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/logs/llm-calls").with(as(ANALYST))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/logs/llm-calls")).andExpect(status().isUnauthorized());
        verifyNoInteractions(logs);
    }

    @Test
    void filtersArePassedThrough() throws Exception {
        Instant from = Instant.parse("2026-11-04T00:00:00Z");
        when(logs.llmCalls(eq(new LlmCallFilter("trace-1", 3L, false, from, null)), eq(1), eq(20)))
                .thenReturn(new PageResponse<>(List.of(), 1, 20, 0));

        mvc.perform(get("/api/v1/admin/logs/llm-calls").with(as(ADMIN))
                        .param("traceId", "trace-1").param("userId", "3").param("success", "false")
                        .param("from", "2026-11-04T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void toolCallLogsAreAdminOnlyAndFilterByDecision() throws Exception {
        when(logs.toolCalls(eq(new ToolCallFilter(null, null, "countAnomalies", Decision.DENIED, null, null)),
                eq(1), eq(20))).thenReturn(new PageResponse<>(List.of(), 1, 20, 0));

        mvc.perform(get("/api/v1/admin/logs/tool-calls").with(as(ANALYST))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/logs/tool-calls").with(as(ADMIN))
                        .param("toolName", "countAnomalies").param("decision", "DENIED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/v1/admin/logs/tool-calls").with(as(ADMIN)).param("decision", "MAYBE"))
                .andExpect(status().isBadRequest());
    }
}
