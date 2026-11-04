package com.example.kbagent.task.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import com.example.kbagent.task.domain.ClaimedTask;
import com.example.kbagent.task.repository.AsyncTaskRepository;
import com.example.kbagent.task.service.TaskService.TaskSubmittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 扫描并领取任务（§4.2）。定时扫描是任务执行的唯一可靠来源；任务所在事务提交后的事件只用来提前扫描一次，
 * 即使事件丢失（例如进程在提交后立即崩溃），任务最多延迟一个扫描间隔。
 */
@Component
@ConditionalOnBooleanProperty(name = "app.task.enabled", matchIfMissing = true)
public class TaskScanner {

    private static final Logger log = LoggerFactory.getLogger(TaskScanner.class);

    private final AsyncTaskRepository tasks;
    private final TaskWorker worker;
    private final TaskProperties properties;
    private final ReentrantLock scanLock = new ReentrantLock();
    private final AtomicBoolean rescanRequested = new AtomicBoolean();

    public TaskScanner(AsyncTaskRepository tasks, TaskWorker worker, TaskProperties properties) {
        this.tasks = tasks;
        this.worker = worker;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.task.scan-interval:5s}", initialDelayString = "${app.task.scan-interval:5s}")
    public void scheduledScan() {
        try {
            scanOnce();
        } catch (RuntimeException e) {
            log.error("task scan failed", e);
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onTaskSubmitted(TaskSubmittedEvent event) {
        if (properties.wakeUpOnCommit()) {
            Thread.ofVirtual().name("task-wakeup").start(this::scheduledScan);
        }
    }

    /** 扫描进行中又收到唤醒时不并发扫描，而是在本轮结束后再扫一轮，避免漏掉刚提交的任务。 */
    void scanOnce() {
        if (!scanLock.tryLock()) {
            rescanRequested.set(true);
            return;
        }
        try {
            do {
                rescanRequested.set(false);
                worker.reapExhausted();
                int free = worker.availableSlots();
                if (free == 0) {
                    return;
                }
                List<ClaimedTask> claimed = tasks.claim(worker.workerId(), free, properties.leaseDuration());
                claimed.forEach(worker::dispatch);
            } while (rescanRequested.get());
        } finally {
            scanLock.unlock();
        }
    }
}
