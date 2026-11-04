package com.example.kbagent.task.service;

import com.example.kbagent.task.domain.ClaimedTask;
import com.example.kbagent.task.domain.TaskType;

/**
 * 某一类任务的处理器，由业务模块实现。任务模块只负责领取、续租、重试和状态流转，不认识具体业务。
 */
public interface TaskHandler {

    TaskType type();

    /**
     * 在工作线程上执行，调用时不在事务中。
     * <p>
     * 成功时必须在“提交业务结果”的同一个事务里调用 {@link TaskContext#complete()}，
     * 这样 fencing 校验和业务写入要么一起成功、要么一起回滚。
     * 抛出 {@link TaskExceptions.NonRetryableTaskException} 表示不必重试；其他异常按可重试处理。
     */
    void execute(ClaimedTask task, TaskContext context);

    /**
     * 任务转为 FAILED 或 DEAD 时，在同一个事务中回调，用于同步业务状态。
     *
     * @param userMessage 可以直接展示给用户的说明；上游原始报错等内部细节只记录在 async_task.last_error 中
     */
    default void onGiveUp(ClaimedTask task, String errorCode, String userMessage) {
    }
}
