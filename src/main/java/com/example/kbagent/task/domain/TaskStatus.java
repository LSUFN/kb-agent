package com.example.kbagent.task.domain;

/**
 * 任务状态（§4.2）。PENDING 和 RUNNING 是活跃状态，同一业务对象同时最多一个活跃任务（uk_task_active）。
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    /** 不可重试的错误，例如 PDF 损坏。 */
    FAILED,
    /** 重试次数耗尽，需要人工处理。 */
    DEAD,
    /** 业务对象已被删除，任务不再需要执行。 */
    CANCELLED
}
