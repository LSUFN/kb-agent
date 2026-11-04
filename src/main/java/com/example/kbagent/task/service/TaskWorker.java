package com.example.kbagent.task.service;

import java.lang.management.ManagementFactory;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import com.example.kbagent.common.trace.TraceContext;
import com.example.kbagent.task.domain.ClaimedTask;
import com.example.kbagent.task.domain.TaskStatus;
import com.example.kbagent.task.domain.TaskType;
import com.example.kbagent.task.repository.AsyncTaskRepository;
import com.example.kbagent.task.service.TaskExceptions.LeaseLostException;
import com.example.kbagent.task.service.TaskExceptions.NonRetryableTaskException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 执行已领取的任务：每个任务一个虚拟线程，并发数由信号量限制（§4.2：每个实例 2 个）。
 * <p>
 * 结果处理：处理器在提交事务中调用 complete() 表示成功；NonRetryableTaskException → FAILED；
 * 其他异常 → 按指数退避重试，最后一次仍失败则 DEAD；LeaseLostException → 租约已被接管，直接放弃。
 */
@Component
@EnableConfigurationProperties(TaskProperties.class)
public class TaskWorker {

    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);

    private final Map<TaskType, TaskHandler> handlers = new EnumMap<>(TaskType.class);
    private final AsyncTaskRepository tasks;
    private final TransactionTemplate transactionTemplate;
    private final TaskProperties properties;
    private final Semaphore slots;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** 本实例的执行者标识，写入 locked_by。 */
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName() + "-"
            + UUID.randomUUID().toString().substring(0, 8);

    public TaskWorker(List<TaskHandler> handlerBeans, AsyncTaskRepository tasks,
                      TransactionTemplate transactionTemplate, TaskProperties properties) {
        for (TaskHandler handler : handlerBeans) {
            if (handlers.put(handler.type(), handler) != null) {
                throw new IllegalStateException("Duplicate TaskHandler for " + handler.type());
            }
        }
        this.tasks = tasks;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.slots = new Semaphore(properties.workerConcurrency());
    }

    public String workerId() {
        return workerId;
    }

    int availableSlots() {
        return slots.availablePermits();
    }

    /** 由扫描器在持有扫描锁时调用，此前已确认有空闲槽位。 */
    void dispatch(ClaimedTask task) {
        if (!slots.tryAcquire()) {
            // 不应发生；任务保持 RUNNING，租约过期后会被重新领取
            log.error("No free slot for claimed task {}, it will be retried after the lease expires", task.id());
            return;
        }
        executor.execute(() -> {
            try {
                run(task);
            } finally {
                slots.release();
            }
        });
    }

    void run(ClaimedTask task) {
        MDC.put(TraceContext.MDC_KEY, "task-" + task.id() + "-" + task.attempt());
        TaskHandler handler = handlers.get(task.type());
        try {
            if (handler == null) {
                throw new NonRetryableTaskException("NO_HANDLER", "No handler for task type " + task.type());
            }
            TaskContext context = new TaskContext(task, tasks, properties.leaseDuration());
            handler.execute(task, context);
            if (!context.completed()) {
                giveUp(task, handler, TaskStatus.FAILED, "HANDLER_DID_NOT_COMPLETE", "处理失败，请联系管理员",
                        "Handler returned without completing the task");
            }
        } catch (LeaseLostException e) {
            log.info("{}; abandoning without writing results", e.getMessage());
        } catch (NonRetryableTaskException e) {
            log.warn("task {} failed permanently: {}", task.id(), e.errorCode(), e);
            giveUp(task, handler, TaskStatus.FAILED, e.errorCode(), e.getMessage(), describe(e));
        } catch (Exception e) {
            String error = describe(e);
            if (task.lastAttempt()) {
                log.error("task {} failed on its last attempt {}", task.id(), task.attempt(), e);
                giveUp(task, handler, TaskStatus.DEAD, "RETRIES_EXHAUSTED", "处理多次失败，请稍后重试或联系管理员", error);
            } else {
                var delay = properties.retryDelay(task.attempt());
                log.warn("task {} attempt {} failed, retrying in {}: {}", task.id(), task.attempt(), delay, error);
                tasks.markRetry(task, delay, error);
            }
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    /** 处理租约过期且已无重试次数的任务（执行者在处理过程中反复死亡）。 */
    void reapExhausted() {
        transactionTemplate.executeWithoutResult(status -> {
            for (ClaimedTask task : tasks.reapExhausted()) {
                log.error("task {} marked DEAD: lease expired on its last attempt {}", task.id(), task.attempt());
                TaskHandler handler = handlers.get(task.type());
                if (handler != null) {
                    handler.onGiveUp(task, "LEASE_EXPIRED", "处理多次中断，请稍后重试或联系管理员");
                }
            }
        });
    }

    /**
     * @param userMessage   交给处理器、最终展示给用户的说明，不含内部细节
     * @param internalError 写入 async_task.last_error 的完整错误（含上游原因），只用于排查
     */
    private void giveUp(ClaimedTask task, TaskHandler handler, TaskStatus status, String errorCode,
                        String userMessage, String internalError) {
        try {
            transactionTemplate.executeWithoutResult(tx -> {
                if (tasks.markGivenUp(task, status, errorCode + ": " + internalError) && handler != null) {
                    handler.onGiveUp(task, errorCode, userMessage);
                }
            });
        } catch (RuntimeException e) {
            // 例如数据库暂时不可用；任务保持 RUNNING，租约过期后会被重新领取
            log.error("failed to record give-up of task {}", task.id(), e);
        }
    }

    /** 异常类型和消息，以及直接原因（上游报错通常在原因里）。 */
    private static String describe(Throwable e) {
        String text = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            text += " <- " + cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
        }
        return text;
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        // 未完成的任务保持 RUNNING，租约过期后由其他实例（或重启后的本实例）重新领取
        executor.shutdownNow();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }
}
