package com.example.kbagent.task.domain;

/**
 * 被某个执行者领取的任务。{@code workerId + attempt} 构成 fencing 令牌：租约过期后任务会被重新领取，
 * attempt 随之加一，旧执行者再用原来的令牌续租或提交都会失败。
 *
 * @param payload JSON 字符串，可能为 null
 */
public record ClaimedTask(long id, TaskType type, long bizId, String payload,
                          int attempt, int maxAttempts, String workerId) {

    public boolean lastAttempt() {
        return attempt >= maxAttempts;
    }
}
