package com.example.kbagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import com.example.kbagent.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientBuilderProperties;
import org.springframework.ai.model.chat.observation.autoconfigure.ChatObservationProperties;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * M0 集成测试（需要 Docker）：容器启动 → Flyway 建表 → 应用上下文可用 → 健康检查与统一响应正常。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ApplicationIT {

    private static final List<String> EXPECTED_TABLES = List.of(
            "sys_user", "knowledge_base", "kb_member", "document", "doc_chunk", "async_task",
            "conversation", "chat_message", "report", "llm_call_log", "tool_call_log",
            "biz_order", "biz_anomaly_event");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ChatClientBuilderProperties chatClientProperties;

    @Autowired
    ChatObservationProperties chatObservationProperties;

    @Autowired
    ToolCallingProperties toolCallingProperties;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void flywayCreatesAllTablesAndPgvectorExtension() {
        Integer vectorExt = jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
        assertThat(vectorExt).isEqualTo(1);

        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).containsAll(EXPECTED_TABLES);

        Integer failedMigrations = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        assertThat(failedMigrations).isZero();
    }

    @Test
    void hnswIndexExistsOnEmbeddingColumn() {
        String indexDef = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_chunk_embedding'", String.class);
        assertThat(indexDef).containsIgnoringCase("hnsw").contains("vector_cosine_ops");
    }

    @Test
    void partialUniqueIndexAllowsReuploadWhileDeleting() {
        jdbc.update("INSERT INTO sys_user(username, password_hash, display_name, role) VALUES ('it_u', 'x', 'u', 'USER')");
        Long userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = 'it_u'", Long.class);
        jdbc.update("INSERT INTO knowledge_base(name, owner_id) VALUES ('kb', ?)", userId);
        Long kbId = jdbc.queryForObject("SELECT id FROM knowledge_base WHERE owner_id = ?", Long.class, userId);
        String hash = "a".repeat(64);

        jdbc.update("""
                INSERT INTO document(kb_id, filename, content_hash, storage_path, size_bytes, status, uploaded_by)
                VALUES (?, 'a.pdf', ?, 'docs/1/a.pdf', 10, 'DELETING', ?)""", kbId, hash, userId);
        // 旧文档处于 DELETING 时，同一文件可以再次上传
        int inserted = jdbc.update("""
                INSERT INTO document(kb_id, filename, content_hash, storage_path, size_bytes, status, uploaded_by)
                VALUES (?, 'a.pdf', ?, 'docs/1/b.pdf', 10, 'PENDING', ?)""", kbId, hash, userId);
        assertThat(inserted).isEqualTo(1);
    }

    @Test
    void healthEndpointIsUp() throws Exception {
        HttpResponse<String> response = get("/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void pingReturnsUnifiedResponseWithTraceId() throws Exception {
        HttpResponse<String> response = get("/api/v1/system/ping");
        assertThat(response.statusCode()).isEqualTo(200);
        String traceId = response.headers().firstValue("X-Trace-Id").orElseThrow();
        assertThat(response.body())
                .contains("\"code\":\"OK\"")
                .contains("\"traceId\":\"" + traceId + "\"");
    }

    @Test
    void anonymousRequestToProtectedPathIsUnified401() throws Exception {
        // M1 起所有 /api/** 默认需要登录；已登录访问不存在的路径返回 404，见 AuthFlowIT
        HttpResponse<String> response = get("/api/v1/does-not-exist");
        assertThat(response.statusCode()).isEqualTo(401);
        String traceId = response.headers().firstValue("X-Trace-Id").orElseThrow();
        assertThat(response.body())
                .contains("\"code\":\"UNAUTHORIZED\"")
                .contains("\"traceId\":\"" + traceId + "\"");
    }

    /**
     * §4.6：Spring AI 自带的 observation 不记录 Prompt、回答和工具内容。
     * 断言的是框架实际绑定的配置对象，升级后即使默认值变化，application.yml 中显式的 false 也必须生效。
     */
    @Test
    void springAiObservationsDoNotRecordContent() {
        assertThat(chatClientProperties.getObservations().isLogPrompt()).isFalse();
        assertThat(chatClientProperties.getObservations().isLogCompletion()).isFalse();
        assertThat(chatObservationProperties.isLogPrompt()).isFalse();
        assertThat(chatObservationProperties.isLogCompletion()).isFalse();
        assertThat(chatObservationProperties.isIncludeErrorLogging()).isFalse();
        assertThat(toolCallingProperties.getObservations().isIncludeContent()).isFalse();
    }
}
