package com.example.kbagent.task.domain;

import java.time.Instant;

/** 任务的当前状态，供业务方展示（例如文档详情中的重试次数）。 */
public record TaskSnapshot(long id, TaskType type, TaskStatus status, int attempt, int maxAttempts,
                           Instant nextRunAt, String lastError) {
}
