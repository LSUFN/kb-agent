package com.example.kbagent.task.service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.observability.SensitiveDataMasker;
import com.example.kbagent.task.domain.TaskRecord;
import com.example.kbagent.task.domain.TaskStatus;
import com.example.kbagent.task.domain.TaskType;
import com.example.kbagent.task.repository.AsyncTaskRepository;
import com.example.kbagent.task.service.TaskService.TaskSubmittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理员查看任务、手动重试 DEAD 任务（§6.5）。
 * 重试次数耗尽的任务不会再被自动执行（§4.2），需要人工确认外部故障已排除后重新排队。
 */
@Service
public class AdminTaskService {

    private static final Logger log = LoggerFactory.getLogger(AdminTaskService.class);

    /** @param lastError 已脱敏 */
    public record AdminTaskView(long id, TaskType type, long bizId, TaskStatus status, int attempt, int maxAttempts,
                                String lastError, Instant nextRunAt, Instant createdAt, Instant updatedAt) {

        static AdminTaskView of(TaskRecord t) {
            return new AdminTaskView(t.id(), t.type(), t.bizId(), t.status(), t.attempt(), t.maxAttempts(),
                    t.lastError() == null ? null : SensitiveDataMasker.mask(t.lastError()), t.nextRunAt(),
                    t.createdAt(), t.updatedAt());
        }
    }

    private final AsyncTaskRepository tasks;
    private final Map<TaskType, TaskHandler> handlers = new EnumMap<>(TaskType.class);
    private final ApplicationEventPublisher events;

    public AdminTaskService(AsyncTaskRepository tasks, List<TaskHandler> handlerBeans,
                            ApplicationEventPublisher events) {
        this.tasks = tasks;
        this.events = events;
        handlerBeans.forEach(handler -> handlers.put(handler.type(), handler));
    }

    /** @param page 从 1 开始 */
    @Transactional(readOnly = true)
    public PageResponse<AdminTaskView> list(TaskStatus status, TaskType type, int page, int size) {
        List<AdminTaskView> items = tasks.findPage(status, type, (page - 1) * size, size).stream()
                .map(AdminTaskView::of)
                .toList();
        return new PageResponse<>(items, page, size, tasks.count(status, type));
    }

    /**
     * 让 DEAD 任务重新排队。先由业务模块锁定并同步业务对象（例如文档 FAILED → PENDING），
     * 再把任务改回 PENDING，两步在同一个事务中：任何一步失败都整体回滚。
     */
    @Transactional
    public AdminTaskView retry(long taskId) {
        TaskRecord task = tasks.findById(taskId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (task.status() != TaskStatus.DEAD) {
            throw new BusinessException(ErrorCode.CONFLICT, "只能重试“重试耗尽”（DEAD）的任务");
        }
        TaskHandler handler = handlers.get(task.type());
        if (handler == null) {
            throw new IllegalStateException("No TaskHandler for " + task.type());
        }
        handler.onManualRetry(task.bizId());
        try {
            if (!tasks.requeueDead(taskId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "任务状态已变化，请刷新后再试");
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "该业务对象已有正在执行的任务");
        }
        log.info("dead task requeued manually taskId={} type={} bizId={}", taskId, task.type(), task.bizId());
        events.publishEvent(new TaskSubmittedEvent(task.type(), task.bizId()));
        return AdminTaskView.of(tasks.findById(taskId).orElseThrow());
    }
}
