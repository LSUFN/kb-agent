package com.example.kbagent.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.example.kbagent.storage.StorageProperties;
import com.example.kbagent.support.ApiClient;
import com.example.kbagent.support.ApiClient.User;
import com.example.kbagent.support.FakeEmbeddingModel;
import com.example.kbagent.support.TestPdfs;
import com.example.kbagent.support.TestcontainersConfiguration;
import com.example.kbagent.user.domain.Role;
import com.example.kbagent.user.repository.SysUserRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
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
import tools.jackson.databind.ObjectMapper;

/**
 * §7 事务边界：调用外部服务期间不占用数据库连接；
 * §7 删除竞争：入库处理过程中删除文档，最终没有残留 chunk，文件被删除。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class IngestionConsistencyIT {

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
    DataSource dataSource;

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

    private long upload() {
        byte[] pdf = TestPdfs.withPages(TestPdfs.pageText(1, 400) + UUID.randomUUID());
        var response = api.upload("/api/v1/knowledge-bases/" + kbId + "/documents", owner, "a.pdf", pdf);
        assertThat(response.status()).isEqualTo(202);
        return response.data().path("id").asLong();
    }

    @Test
    void noDatabaseConnectionIsHeldWhileWaitingForTheEmbeddingService() throws Exception {
        HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        embeddings.delayEachCall(Duration.ofSeconds(2));

        long documentId = upload();
        assertThat(embeddings.awaitCallStarted(Duration.ofSeconds(10))).isTrue();

        // Embedding 调用阻塞的 2 秒内反复采样：如果执行者持有连接，活跃连接数不可能降到 0
        int minActive = Integer.MAX_VALUE;
        for (int i = 0; i < 10; i++) {
            minActive = Math.min(minActive, pool.getActiveConnections());
            Thread.sleep(100);
        }
        assertThat(minActive).isZero();

        api.awaitStatus(documentId, owner, "READY");
        assertThat(embeddings.calledInsideTransaction()).isFalse();
    }

    @Test
    void deletingADocumentWhileItIsBeingIngestedLeavesNothingBehind() throws Exception {
        embeddings.closeGate();
        long documentId = upload();
        // 入库正处于 ② 计算阶段（卡在 Embedding 调用上）
        assertThat(embeddings.awaitCallStarted(Duration.ofSeconds(10))).isTrue();

        assertThat(api.delete("/api/v1/documents/" + documentId, owner).status()).isEqualTo(202);
        // 放行：执行者续租时发现任务已被取消，放弃写入
        embeddings.openGate();

        api.awaitDocument(documentId, owner, r -> r.status() == 404, Duration.ofSeconds(20));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM doc_chunk WHERE document_id = ?", Long.class, documentId))
                .isZero();
        assertThat(filesOfKnowledgeBase()).isZero();
        assertThat(jdbc.queryForList("SELECT task_type || ':' || status FROM async_task WHERE biz_id = ? ORDER BY id",
                String.class, documentId))
                .containsExactly("INGEST_DOCUMENT:CANCELLED", "DELETE_FILE:SUCCEEDED");
    }

    private long filesOfKnowledgeBase() throws IOException {
        Path dir = storageProperties.root().resolve("docs").resolve(Long.toString(kbId));
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }
}
