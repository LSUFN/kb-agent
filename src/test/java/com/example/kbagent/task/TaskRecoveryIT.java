package com.example.kbagent.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import com.example.kbagent.storage.StorageProperties;
import com.example.kbagent.support.ApiClient;
import com.example.kbagent.support.ApiClient.User;
import com.example.kbagent.support.FakeEmbeddingModel;
import com.example.kbagent.support.TestPdfs;
import com.example.kbagent.support.TestcontainersConfiguration;
import com.example.kbagent.user.domain.Role;
import com.example.kbagent.user.repository.SysUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * §7 任务恢复：执行者死亡后另一个执行者接管、旧执行者的提交被 fencing 拒绝；
 * 提交事件丢失时任务仍被扫描器执行；上游前两次失败、第三次成功时任务最终成功且 attempt 为 3。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class TaskRecoveryIT {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    SysUserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FakeEmbeddingModel embeddings;

    @Autowired
    StorageProperties storageProperties;

    private ApiClient api;
    private User owner;
    private long kbId;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port, json);
        owner = api.createUserAndLogin(users, passwordEncoder, Role.USER);
        kbId = api.createKnowledgeBase(owner, "PRIVATE");
    }

    /** 假模型由所有集成测试共用：开始前和结束后都重置，避免其他测试类留下的状态影响断言。 */
    @BeforeEach
    @AfterEach
    void resetFakes() {
        embeddings.reset();
    }

    private long upload(byte[] pdf) {
        var response = api.upload("/api/v1/knowledge-bases/" + kbId + "/documents", owner, "a.pdf", pdf);
        assertThat(response.status()).as(response.body().toString()).isEqualTo(202);
        return response.data().path("id").asLong();
    }

    private static byte[] uniquePdf() {
        return TestPdfs.withPages(TestPdfs.pageText(1, 400) + UUID.randomUUID());
    }

    private int ingestAttempt(long documentId) {
        return jdbc.queryForObject("""
                SELECT attempt FROM async_task WHERE biz_id = ? AND task_type = 'INGEST_DOCUMENT'
                 ORDER BY id DESC LIMIT 1""", Integer.class, documentId);
    }

    @Test
    void transientUpstreamFailuresAreRetriedUntilTheTaskSucceeds() {
        embeddings.failNextCalls(2);

        long documentId = upload(uniquePdf());
        JsonNode ready = api.awaitStatus(documentId, owner, "READY");

        assertThat(ready.path("task").path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(ready.path("task").path("attempt").asInt()).isEqualTo(3);
        assertThat(embeddings.calls()).isEqualTo(3);
    }

    @Test
    void exhaustedRetriesMarkTheTaskDeadAndTheDocumentFailedWithoutLeakingUpstreamErrors() {
        embeddings.failNextCalls(Integer.MAX_VALUE);

        long documentId = upload(uniquePdf());
        JsonNode failed = api.awaitStatus(documentId, owner, "FAILED");

        assertThat(failed.path("task").path("status").asString()).isEqualTo("DEAD");
        assertThat(failed.path("task").path("attempt").asInt()).isEqualTo(5);
        assertThat(failed.path("errorCode").asString()).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(failed.path("errorMessage").asString()).doesNotContain("503");
        // 上游原始错误只记录在任务表中，供排查使用
        assertThat(jdbc.queryForObject("SELECT last_error FROM async_task WHERE biz_id = ?", String.class, documentId))
                .contains("503 Service Unavailable");
    }

    @Test
    void aTaskWhoseWorkerDiedIsTakenOverAndTheOldWorkerCannotWriteResults() throws Exception {
        embeddings.closeGate();
        long documentId = upload(uniquePdf());
        // 执行者 A 领取任务（attempt 1）后卡在 Embedding 调用上
        assertThat(embeddings.awaitCallStarted(Duration.ofSeconds(10))).isTrue();

        // 模拟执行者 A 死亡：它的租约到期
        jdbc.update("UPDATE async_task SET lease_until = now() - interval '1 second' WHERE biz_id = ?", documentId);
        // 扫描器重新领取（attempt 2），执行者 B 同样卡在 Embedding 调用上
        assertThat(embeddings.awaitCallStarted(Duration.ofSeconds(10))).isTrue();
        assertThat(ingestAttempt(documentId)).isEqualTo(2);

        // 放行：A 用 attempt 1 的令牌续租失败、放弃；只有 B 的结果被写入
        embeddings.openGate();
        JsonNode ready = api.awaitStatus(documentId, owner, "READY");

        assertThat(ready.path("task").path("attempt").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM doc_chunk WHERE document_id = ?", Long.class, documentId))
                .isEqualTo(ready.path("chunkCount").asLong());
    }

    @Test
    void tasksAreExecutedByTheScannerEvenIfTheCommitEventIsLost() throws Exception {
        // 绕过上传接口直接写文件、文档和任务，不发布任何事件，相当于“提交后唤醒”的事件丢失了
        byte[] pdf = uniquePdf();
        String storagePath = "docs/" + kbId + "/" + UUID.randomUUID() + ".pdf";
        Path file = storageProperties.root().resolve(storagePath);
        Files.createDirectories(file.getParent());
        Files.write(file, pdf);
        Long documentId = jdbc.queryForObject("""
                        INSERT INTO document(kb_id, filename, content_hash, storage_path, size_bytes, status, uploaded_by)
                        VALUES (?, 'lost-event.pdf', ?, ?, ?, 'PENDING', ?) RETURNING id""", Long.class,
                kbId, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf)), storagePath,
                pdf.length, owner.id());
        jdbc.update("INSERT INTO async_task(task_type, biz_id, status) VALUES ('INGEST_DOCUMENT', ?, 'PENDING')",
                documentId);

        assertThat(api.awaitStatus(documentId, owner, "READY").path("chunkCount").asInt()).isPositive();
    }
}
