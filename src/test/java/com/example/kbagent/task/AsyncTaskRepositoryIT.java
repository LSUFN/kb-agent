package com.example.kbagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import com.example.kbagent.support.TestcontainersConfiguration;
import com.example.kbagent.task.domain.ClaimedTask;
import com.example.kbagent.task.domain.TaskStatus;
import com.example.kbagent.task.domain.TaskType;
import com.example.kbagent.task.repository.AsyncTaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 直接验证 async_task 的 SQL（§4.2）。关闭扫描器，避免后台扫描抢走测试中的任务。
 */
@SpringBootTest(properties = "app.task.enabled=false")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AsyncTaskRepositoryIT {

    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    AsyncTaskRepository tasks;

    @Autowired
    JdbcTemplate jdbc;

    private long newTask() {
        return tasks.insertPending(TaskType.DELETE_FILE, ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE),
                "{\"storagePath\":\"docs/x.pdf\"}");
    }

    private ClaimedTask claimOnly(long taskId, String workerId) {
        List<ClaimedTask> claimed = tasks.claim(workerId, 100, LEASE);
        return claimed.stream().filter(t -> t.id() == taskId).findFirst().orElseThrow();
    }

    private void expireLease(long taskId) {
        jdbc.update("UPDATE async_task SET lease_until = now() - interval '1 second' WHERE id = ?", taskId);
    }

    private String status(long taskId) {
        return jdbc.queryForObject("SELECT status FROM async_task WHERE id = ?", String.class, taskId);
    }

    @Test
    void concurrentClaimsNeverHandOutTheSameTaskTwice() throws Exception {
        Set<Long> created = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            created.add(newTask());
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<ClaimedTask>>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) {
                String worker = "worker-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return tasks.claim(worker, 10, LEASE);
                }));
            }
            start.countDown();
            List<Long> claimedIds = new ArrayList<>();
            for (Future<List<ClaimedTask>> result : results) {
                result.get().forEach(task -> claimedIds.add(task.id()));
            }
            // 4 个执行者共能领取 40 个，足够把 30 个任务全部领走；SKIP LOCKED 保证没有任务被领取两次
            assertThat(claimedIds).doesNotHaveDuplicates().containsAll(created);
        }
    }

    @Test
    void aStaleWorkerIsFencedOffAfterItsLeaseWasTakenOver() {
        long taskId = newTask();
        ClaimedTask workerA = claimOnly(taskId, "worker-a");
        expireLease(taskId);

        ClaimedTask workerB = claimOnly(taskId, "worker-b");

        assertThat(workerB.attempt()).isEqualTo(workerA.attempt() + 1);
        assertThat(tasks.renewLease(workerA, LEASE)).isFalse();
        assertThat(tasks.markSucceeded(workerA)).isFalse();
        assertThat(tasks.markRetry(workerA, Duration.ZERO, "late")).isFalse();
        assertThat(tasks.markSucceeded(workerB)).isTrue();
        assertThat(status(taskId)).isEqualTo("SUCCEEDED");
    }

    @Test
    void retriedTaskIsNotClaimableBeforeItsNextRunTime() {
        long taskId = newTask();
        ClaimedTask claimed = claimOnly(taskId, "worker-a");

        assertThat(tasks.markRetry(claimed, Duration.ofHours(1), "upstream 503")).isTrue();

        assertThat(tasks.claim("worker-b", 100, LEASE)).extracting(ClaimedTask::id).doesNotContain(taskId);
        assertThat(status(taskId)).isEqualTo("PENDING");
        assertThat(tasks.findLatest(TaskType.DELETE_FILE, claimed.bizId()).orElseThrow().lastError())
                .isEqualTo("upstream 503");
    }

    @Test
    void anExpiredLeaseOnTheLastAttemptIsReapedInsteadOfReclaimed() {
        long taskId = newTask();
        jdbc.update("UPDATE async_task SET max_attempts = 1 WHERE id = ?", taskId);
        claimOnly(taskId, "worker-a");
        expireLease(taskId);

        assertThat(tasks.claim("worker-b", 100, LEASE)).extracting(ClaimedTask::id).doesNotContain(taskId);
        assertThat(tasks.reapExhausted()).extracting(ClaimedTask::id).contains(taskId);
        assertThat(status(taskId)).isEqualTo("DEAD");
    }

    @Test
    void cancellingATaskFencesOffItsCurrentWorker() {
        long taskId = newTask();
        ClaimedTask claimed = claimOnly(taskId, "worker-a");

        assertThat(tasks.cancelActive(TaskType.DELETE_FILE, claimed.bizId())).isEqualTo(1);

        assertThat(tasks.markSucceeded(claimed)).isFalse();
        assertThat(status(taskId)).isEqualTo(TaskStatus.CANCELLED.name());
    }

    @Test
    void onlyOneActiveTaskPerBusinessObject() {
        long bizId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        tasks.insertPending(TaskType.INGEST_DOCUMENT, bizId, null);

        assertThatThrownBy(() -> tasks.insertPending(TaskType.INGEST_DOCUMENT, bizId, null))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
