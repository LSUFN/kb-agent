package com.example.kbagent.task.service;

import com.example.kbagent.task.domain.ClaimedTask;

/**
 * 任务处理器用来表达执行结果的异常。未在这里列出的异常一律按“可重试”处理。
 */
public final class TaskExceptions {

    private TaskExceptions() {
    }

    /** 不可重试的错误（例如 PDF 损坏）：任务直接转为 FAILED，并通知处理器。 */
    public static class NonRetryableTaskException extends RuntimeException {

        private final String errorCode;

        public NonRetryableTaskException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public NonRetryableTaskException(String errorCode, String message, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }

    /** 明确可重试的错误（例如上游 429/5xx）。 */
    public static class RetryableTaskException extends RuntimeException {

        public RetryableTaskException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * fencing 校验失败：租约已被其他执行者接管，或任务已被取消。当前执行者必须放弃，不能再写任何结果。
     */
    public static class LeaseLostException extends RuntimeException {

        public LeaseLostException(ClaimedTask task) {
            super("Lease lost for task " + task.id() + " (attempt " + task.attempt() + ", worker " + task.workerId() + ")");
        }
    }
}
