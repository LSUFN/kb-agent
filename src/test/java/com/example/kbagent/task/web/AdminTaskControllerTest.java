package com.example.kbagent.task.web;

import static com.example.kbagent.support.TestActors.ADMIN;
import static com.example.kbagent.support.TestActors.ANALYST;
import static com.example.kbagent.support.TestActors.USER;
import static com.example.kbagent.support.TestActors.as;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.support.SecuredWebMvcTest;
import com.example.kbagent.task.domain.TaskStatus;
import com.example.kbagent.task.domain.TaskType;
import com.example.kbagent.task.service.AdminTaskService;
import com.example.kbagent.task.service.AdminTaskService.AdminTaskView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SecuredWebMvcTest(AdminTaskController.class)
class AdminTaskControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AdminTaskService tasks;

    private static AdminTaskView view(TaskStatus status) {
        Instant now = Instant.parse("2026-11-05T00:00:00Z");
        return new AdminTaskView(7, TaskType.INGEST_DOCUMENT, 42, status, 0, 5, "503 Service Unavailable", now, now,
                now);
    }

    @Test
    void onlyAdminCanListOrRetryTasks() throws Exception {
        mvc.perform(get("/api/v1/admin/tasks").with(as(USER))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/tasks").with(as(ANALYST))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/tasks/7/retry").with(as(ANALYST))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/tasks")).andExpect(status().isUnauthorized());
        verifyNoInteractions(tasks);
    }

    @Test
    void filtersArePassedThrough() throws Exception {
        when(tasks.list(TaskStatus.DEAD, TaskType.INGEST_DOCUMENT, 2, 10))
                .thenReturn(new PageResponse<>(List.of(view(TaskStatus.DEAD)), 2, 10, 11));

        mvc.perform(get("/api/v1/admin/tasks").with(as(ADMIN))
                        .param("status", "DEAD").param("type", "INGEST_DOCUMENT").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.items[0].bizId").value(42));
        mvc.perform(get("/api/v1/admin/tasks").with(as(ADMIN)).param("status", "BROKEN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retryReturnsTheRequeuedTaskOrConflict() throws Exception {
        when(tasks.retry(7)).thenReturn(view(TaskStatus.PENDING));
        when(tasks.retry(8)).thenThrow(new BusinessException(ErrorCode.CONFLICT, "只能重试“重试耗尽”（DEAD）的任务"));

        mvc.perform(post("/api/v1/admin/tasks/7/retry").with(as(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        mvc.perform(post("/api/v1/admin/tasks/8/retry").with(as(ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }
}
