package com.example.kbagent.task.service;

import java.util.Optional;

import com.example.kbagent.task.domain.TaskSnapshot;
import com.example.kbagent.task.domain.TaskType;
import com.example.kbagent.task.repository.AsyncTaskRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 其他模块提交、取消、查询任务的入口。 */
@Service
public class TaskService {

    /** 任务所在的事务提交后发布，扫描器据此提前扫描一次。 */
    public record TaskSubmittedEvent(TaskType type, long bizId) {
    }

    private final AsyncTaskRepository tasks;
    private final ApplicationEventPublisher events;

    public TaskService(AsyncTaskRepository tasks, ApplicationEventPublisher events) {
        this.tasks = tasks;
        this.events = events;
    }

    /**
     * 提交任务。必须在业务事务中调用，任务与业务数据一起提交：事务回滚时任务也不存在。
     * 同一业务对象已有活跃任务时抛出 DuplicateKeyException。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long submit(TaskType type, long bizId, String payloadJson) {
        long id = tasks.insertPending(type, bizId, payloadJson);
        events.publishEvent(new TaskSubmittedEvent(type, bizId));
        return id;
    }

    /** 取消业务对象的活跃任务。正在执行的任务会在下一次续租或提交时发现自己已被取消。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int cancelActive(TaskType type, long bizId) {
        return tasks.cancelActive(type, bizId);
    }

    @Transactional(readOnly = true)
    public Optional<TaskSnapshot> findLatest(TaskType type, long bizId) {
        return tasks.findLatest(type, bizId);
    }
}
