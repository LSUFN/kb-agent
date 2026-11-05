package com.example.kbagent.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import com.example.kbagent.support.ApiClient;
import com.example.kbagent.support.ApiClient.Response;
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
 * 管理员手动重试 DEAD 任务（§6.5）：业务状态与任务一起回到排队状态；业务对象已不需要重试时整体回滚。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AdminTaskIT {

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

    private ApiClient api;
    private User owner;
    private User admin;
    private long kbId;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port, json);
        owner = api.createUserAndLogin(users, passwordEncoder, Role.USER);
        admin = api.createUserAndLogin(users, passwordEncoder, Role.ADMIN);
        kbId = api.createKnowledgeBase(owner, "PRIVATE");
    }

    /** 假模型由所有集成测试共用：开始前和结束后都重置，避免其他测试类留下的状态影响断言。 */
    @BeforeEach
    @AfterEach
    void resetFakes() {
        embeddings.reset();
    }

    private long upload() {
        byte[] pdf = TestPdfs.withPages(TestPdfs.pageText(1, 400) + UUID.randomUUID());
        Response response = api.upload("/api/v1/knowledge-bases/" + kbId + "/documents", owner, "a.pdf", pdf);
        assertThat(response.status()).as(response.body().toString()).isEqualTo(202);
        return response.data().path("id").asLong();
    }

    private long ingestTaskOf(long documentId) {
        return jdbc.queryForObject("""
                SELECT id FROM async_task WHERE biz_id = ? AND task_type = 'INGEST_DOCUMENT'
                 ORDER BY id DESC LIMIT 1""", Long.class, documentId);
    }

    private String taskStatus(long taskId) {
        return jdbc.queryForObject("SELECT status FROM async_task WHERE id = ?", String.class, taskId);
    }

    private long deadDocument() {
        embeddings.failNextCalls(Integer.MAX_VALUE);
        long documentId = upload();
        JsonNode failed = api.awaitStatus(documentId, owner, "FAILED");
        assertThat(failed.path("task").path("status").asString()).isEqualTo("DEAD");
        embeddings.reset();
        return documentId;
    }

    @Test
    void deadIngestionTaskIsListedAndRetriedUntilTheDocumentIsReady() {
        long documentId = deadDocument();
        long taskId = ingestTaskOf(documentId);

        Response list = api.get("/api/v1/admin/tasks?status=DEAD&type=INGEST_DOCUMENT&size=100", admin);
        assertThat(list.status()).isEqualTo(200);
        JsonNode item = null;
        for (JsonNode node : list.data().path("items")) {
            if (node.path("id").asLong() == taskId) {
                item = node;
            }
        }
        assertThat(item).as("dead task should be listed").isNotNull();
        assertThat(item.path("bizId").asLong()).isEqualTo(documentId);
        assertThat(item.path("attempt").asInt()).isEqualTo(5);
        assertThat(item.path("lastError").asString()).contains("503");

        Response retried = api.post("/api/v1/admin/tasks/" + taskId + "/retry", admin, null);
        assertThat(retried.status()).as(retried.body().toString()).isEqualTo(200);
        assertThat(retried.data().path("status").asString()).isEqualTo("PENDING");
        assertThat(retried.data().path("attempt").asInt()).isZero();

        JsonNode ready = api.awaitStatus(documentId, owner, "READY");
        assertThat(ready.path("chunkCount").asInt()).isPositive();
        assertThat(ready.path("errorCode").isNull()).isTrue();
        // 重新排队的是同一个任务，而不是新建一个
        assertThat(ingestTaskOf(documentId)).isEqualTo(taskId);
        assertThat(taskStatus(taskId)).isEqualTo("SUCCEEDED");
        assertThat(ready.path("task").path("attempt").asInt()).isEqualTo(1);
    }

    @Test
    void onlyDeadTasksCanBeRetried() {
        long documentId = upload();
        api.awaitStatus(documentId, owner, "READY");
        long taskId = ingestTaskOf(documentId);

        Response notDead = api.post("/api/v1/admin/tasks/" + taskId + "/retry", admin, null);
        assertThat(notDead.status()).isEqualTo(409);
        assertThat(taskStatus(taskId)).isEqualTo("SUCCEEDED");

        assertThat(api.post("/api/v1/admin/tasks/" + Long.MAX_VALUE + "/retry", admin, null).status()).isEqualTo(404);
    }

    @Test
    void retryIsRolledBackWhenTheDocumentWasAlreadyReprocessed() {
        long documentId = deadDocument();
        long deadTaskId = ingestTaskOf(documentId);
        // 文档所有者已经在文档页点了“重试”，并且处理成功
        assertThat(api.post("/api/v1/documents/" + documentId + "/reprocess", owner, null).status()).isEqualTo(202);
        api.awaitStatus(documentId, owner, "READY");

        Response stale = api.post("/api/v1/admin/tasks/" + deadTaskId + "/retry", admin, null);
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().path("message").asString()).contains("READY");
        assertThat(taskStatus(deadTaskId)).isEqualTo("DEAD");
        assertThat(api.get("/api/v1/documents/" + documentId, owner).data().path("status").asString())
                .isEqualTo("READY");
    }
}
