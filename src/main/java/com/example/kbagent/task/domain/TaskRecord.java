package com.example.kbagent.task.domain;

import java.time.Instant;

/**
 * 管理端看到的任务（§6.5）。
 *
 * @param lastError 最后一次失败的完整错误（含上游原因），只供管理员排查，返回前还要再脱敏一次
 */
public record TaskRecord(long id, TaskType type, long bizId, TaskStatus status, int attempt, int maxAttempts,
                         String lastError, Instant nextRunAt, Instant createdAt, Instant updatedAt) {
}
