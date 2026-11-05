package com.example.kbagent.task.web;

import com.example.kbagent.common.api.ApiResponse;
import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.task.domain.TaskStatus;
import com.example.kbagent.task.domain.TaskType;
import com.example.kbagent.task.service.AdminTaskService;
import com.example.kbagent.task.service.AdminTaskService.AdminTaskView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 异步任务管理（§6.5），只对 ADMIN 开放。 */
@RestController
@RequestMapping("/api/v1/admin/tasks")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTaskController {

    private final AdminTaskService tasks;

    public AdminTaskController(AdminTaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminTaskView>> list(
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) TaskType type,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(tasks.list(status, type, page, size));
    }

    /** 只能重试 DEAD 的任务；业务对象已不需要重试或已有活跃任务时返回 409。 */
    @PostMapping("/{id}/retry")
    public ApiResponse<AdminTaskView> retry(@PathVariable long id) {
        return ApiResponse.ok(tasks.retry(id));
    }
}
