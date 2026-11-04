package com.example.kbagent.task.service;

import java.time.Duration;

import com.example.kbagent.task.domain.ClaimedTask;
import com.example.kbagent.task.repository.AsyncTaskRepository;
import com.example.kbagent.task.service.TaskExceptions.LeaseLostException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 处理器在执行过程中与任务模块交互的入口。 */
public final class TaskContext {

    private final ClaimedTask task;
    private final AsyncTaskRepository tasks;
    private final Duration lease;
    private volatile boolean completed;

    TaskContext(ClaimedTask task, AsyncTaskRepository tasks, Duration lease) {
        this.task = task;
        this.tasks = tasks;
        this.lease = lease;
    }

    /** 续租。租约已被接管或任务已被取消时抛出 LeaseLostException，处理器必须立即停止。 */
    public void renewLease() {
        if (!tasks.renewLease(task, lease)) {
            throw new LeaseLostException(task);
        }
    }

    /** 带 fencing 地把任务标记为成功。必须在提交业务结果的事务中调用。 */
    public void complete() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("TaskContext.complete() 必须在提交业务结果的事务中调用");
        }
        if (!tasks.markSucceeded(task)) {
            throw new LeaseLostException(task);
        }
        completed = true;
    }

    boolean completed() {
        return completed;
    }
}
