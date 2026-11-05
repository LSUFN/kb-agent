package com.example.kbagent.task.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.kbagent.task.domain.ClaimedTask;
import com.example.kbagent.task.domain.TaskRecord;
import com.example.kbagent.task.domain.TaskSnapshot;
import com.example.kbagent.task.domain.TaskStatus;
import com.example.kbagent.task.domain.TaskType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * async_task 的全部 SQL（§4.2）。
 * <p>
 * 执行者的每一次状态变更都带 fencing 条件 {@code locked_by + attempt + status = 'RUNNING'}：
 * 影响行数为 0 说明租约已被其他执行者接管或任务已被取消，调用方必须放弃。
 */
@Repository
public class AsyncTaskRepository {

    private static final int MAX_ERROR_LENGTH = 1000;
    private static final String RETURNING = "RETURNING id, task_type, biz_id, payload::text AS payload, attempt, max_attempts, locked_by";
    private static final String FENCE = "id = :id AND locked_by = :workerId AND attempt = :attempt AND status = 'RUNNING'";

    private final JdbcClient jdbc;

    public AsyncTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建待执行任务。同一业务对象已有活跃任务时违反 uk_task_active，抛出 DuplicateKeyException。 */
    public long insertPending(TaskType type, long bizId, String payloadJson) {
        String payload = payloadJson == null ? "NULL" : "CAST(:payload AS jsonb)";
        var spec = jdbc.sql("INSERT INTO async_task(task_type, biz_id, payload, status) VALUES (:type, :bizId, "
                        + payload + ", 'PENDING') RETURNING id")
                .param("type", type.name())
                .param("bizId", bizId);
        if (payloadJson != null) {
            spec = spec.param("payload", payloadJson);
        }
        return spec.query(Long.class).single();
    }

    /**
     * 领取最多 limit 个可执行的任务：到期的 PENDING，以及租约已过期（执行者已死）且还有重试次数的 RUNNING。
     * SKIP LOCKED 保证多个实例并发领取时不会拿到同一个任务。
     */
    public List<ClaimedTask> claim(String workerId, int limit, Duration lease) {
        return jdbc.sql("""
                        UPDATE async_task t
                           SET status = 'RUNNING', attempt = t.attempt + 1, locked_by = :workerId,
                               lease_until = now() + :leaseMillis * interval '1 millisecond', updated_at = now()
                         WHERE t.id IN (
                               SELECT id FROM async_task
                                WHERE (status = 'PENDING' AND next_run_at <= now())
                                   OR (status = 'RUNNING' AND lease_until < now() AND attempt < max_attempts)
                                ORDER BY next_run_at
                                LIMIT :limit
                                FOR UPDATE SKIP LOCKED)
                        """ + RETURNING)
                .param("workerId", workerId)
                .param("leaseMillis", lease.toMillis())
                .param("limit", limit)
                .query(AsyncTaskRepository::mapClaimed)
                .list();
    }

    public boolean renewLease(ClaimedTask task, Duration lease) {
        return jdbc.sql("UPDATE async_task SET lease_until = now() + :leaseMillis * interval '1 millisecond', "
                        + "updated_at = now() WHERE " + FENCE)
                .param("leaseMillis", lease.toMillis())
                .params(fence(task))
                .update() == 1;
    }

    public boolean markSucceeded(ClaimedTask task) {
        return jdbc.sql("UPDATE async_task SET status = 'SUCCEEDED', lease_until = NULL, last_error = NULL, "
                        + "updated_at = now() WHERE " + FENCE)
                .params(fence(task))
                .update() == 1;
    }

    /** 回到 PENDING，delay 之后再次被领取。 */
    public boolean markRetry(ClaimedTask task, Duration delay, String error) {
        return jdbc.sql("UPDATE async_task SET status = 'PENDING', "
                        + "next_run_at = now() + :delayMillis * interval '1 millisecond', locked_by = NULL, "
                        + "lease_until = NULL, last_error = :error, updated_at = now() WHERE " + FENCE)
                .param("delayMillis", delay.toMillis())
                .param("error", truncate(error))
                .params(fence(task))
                .update() == 1;
    }

    /** 转为 FAILED 或 DEAD，不再自动执行。 */
    public boolean markGivenUp(ClaimedTask task, TaskStatus status, String error) {
        if (status != TaskStatus.FAILED && status != TaskStatus.DEAD) {
            throw new IllegalArgumentException("status must be FAILED or DEAD: " + status);
        }
        return jdbc.sql("UPDATE async_task SET status = :status, lease_until = NULL, last_error = :error, "
                        + "updated_at = now() WHERE " + FENCE)
                .param("status", status.name())
                .param("error", truncate(error))
                .params(fence(task))
                .update() == 1;
    }

    /**
     * 租约过期且重试次数已经用完的任务直接转为 DEAD。执行者在处理过程中反复死亡（例如内存溢出）时，
     * 任务走不到正常的失败分支，需要靠这里兜底，否则会被无限次重新领取。
     */
    public List<ClaimedTask> reapExhausted() {
        return jdbc.sql("""
                        UPDATE async_task
                           SET status = 'DEAD', lease_until = NULL, updated_at = now(),
                               last_error = 'Lease expired on the last attempt: the worker kept dying while executing'
                         WHERE status = 'RUNNING' AND lease_until < now() AND attempt >= max_attempts
                        """ + RETURNING)
                .query(AsyncTaskRepository::mapClaimed)
                .list();
    }

    /** 业务对象被删除时取消其活跃任务。返回取消的任务数。 */
    public int cancelActive(TaskType type, long bizId) {
        return jdbc.sql("UPDATE async_task SET status = 'CANCELLED', lease_until = NULL, updated_at = now() "
                        + "WHERE task_type = :type AND biz_id = :bizId AND status IN ('PENDING', 'RUNNING')")
                .param("type", type.name())
                .param("bizId", bizId)
                .update();
    }

    public Optional<TaskSnapshot> findLatest(TaskType type, long bizId) {
        return jdbc.sql("""
                        SELECT id, task_type, status, attempt, max_attempts, next_run_at, last_error
                          FROM async_task WHERE task_type = :type AND biz_id = :bizId
                         ORDER BY id DESC LIMIT 1""")
                .param("type", type.name())
                .param("bizId", bizId)
                .query((rs, n) -> new TaskSnapshot(rs.getLong("id"), TaskType.valueOf(rs.getString("task_type")),
                        TaskStatus.valueOf(rs.getString("status")), rs.getInt("attempt"), rs.getInt("max_attempts"),
                        rs.getObject("next_run_at", OffsetDateTime.class).toInstant(), rs.getString("last_error")))
                .optional();
    }

    // ---------- 管理端（§6.5） ----------

    private static final String RECORD_COLUMNS =
            "id, task_type, biz_id, status, attempt, max_attempts, last_error, next_run_at, created_at, updated_at";

    public Optional<TaskRecord> findById(long id) {
        return jdbc.sql("SELECT " + RECORD_COLUMNS + " FROM async_task WHERE id = :id")
                .param("id", id)
                .query(AsyncTaskRepository::mapRecord)
                .optional();
    }

    /** 按 id 倒序分页；status、type 为 null 表示不过滤。 */
    public List<TaskRecord> findPage(TaskStatus status, TaskType type, int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String where = filter(status, type, params);
        return jdbc.sql("SELECT " + RECORD_COLUMNS + " FROM async_task" + where
                        + " ORDER BY id DESC OFFSET :offset LIMIT :limit")
                .params(params)
                .param("offset", offset)
                .param("limit", limit)
                .query(AsyncTaskRepository::mapRecord)
                .list();
    }

    public long count(TaskStatus status, TaskType type) {
        Map<String, Object> params = new HashMap<>();
        return jdbc.sql("SELECT count(*) FROM async_task" + filter(status, type, params))
                .params(params)
                .query(Long.class)
                .single();
    }

    /**
     * 人工重试：DEAD 的任务重新排队，尝试次数清零（重新获得完整的重试次数），保留 last_error 作为上次失败的原因。
     * 返回 false 表示任务已经不是 DEAD（例如被并发重试）。
     * 同一业务对象已有活跃任务时违反 uk_task_active，抛出 DuplicateKeyException。
     */
    public boolean requeueDead(long id) {
        return jdbc.sql("UPDATE async_task SET status = 'PENDING', attempt = 0, next_run_at = now(), locked_by = NULL, "
                        + "lease_until = NULL, updated_at = now() WHERE id = :id AND status = 'DEAD'")
                .param("id", id)
                .update() == 1;
    }

    private static String filter(TaskStatus status, TaskType type, Map<String, Object> params) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (status != null) {
            where.append(" AND status = :status");
            params.put("status", status.name());
        }
        if (type != null) {
            where.append(" AND task_type = :type");
            params.put("type", type.name());
        }
        return where.toString();
    }

    private static TaskRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new TaskRecord(rs.getLong("id"), TaskType.valueOf(rs.getString("task_type")), rs.getLong("biz_id"),
                TaskStatus.valueOf(rs.getString("status")), rs.getInt("attempt"), rs.getInt("max_attempts"),
                rs.getString("last_error"), rs.getObject("next_run_at", OffsetDateTime.class).toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static Map<String, Object> fence(ClaimedTask task) {
        return Map.of("id", task.id(), "workerId", task.workerId(), "attempt", task.attempt());
    }

    private static ClaimedTask mapClaimed(ResultSet rs, int rowNum) throws SQLException {
        return new ClaimedTask(rs.getLong("id"), TaskType.valueOf(rs.getString("task_type")), rs.getLong("biz_id"),
                rs.getString("payload"), rs.getInt("attempt"), rs.getInt("max_attempts"), rs.getString("locked_by"));
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
