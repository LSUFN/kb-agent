package com.example.kbagent.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import com.example.kbagent.storage.StorageProperties;
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
 * §7 入库：上传后轮询到 READY，chunk 数量和页码正确；重复上传幂等；并发上传同一个文件只留下一条记录和一个文件。
 * 另外覆盖删除、重新处理、文件校验和知识库权限语义。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DocumentIngestionIT {

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

    @BeforeEach
    void setUp() {
        api = new ApiClient(port, json);
    }

    /** 假模型由所有集成测试共用：开始前和结束后都重置，避免其他测试类留下的状态影响断言。 */
    @BeforeEach
    @AfterEach
    void resetFakes() {
        embeddings.reset();
    }

    private User newUser() {
        return api.createUserAndLogin(users, passwordEncoder, Role.USER);
    }

    private long upload(User user, long kbId, byte[] pdf, int expectedStatus) {
        Response response = api.upload("/api/v1/knowledge-bases/" + kbId + "/documents", user, "制度.pdf", pdf);
        assertThat(response.status()).as(response.body().toString()).isEqualTo(expectedStatus);
        return response.data().path("id").asLong();
    }

    private long filesOf(long kbId) throws IOException {
        Path dir = storageProperties.root().resolve("docs").resolve(Long.toString(kbId));
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }

    private long chunksOf(long documentId) {
        return jdbc.queryForObject("SELECT count(*) FROM doc_chunk WHERE document_id = ?", Long.class, documentId);
    }

    @Test
    void uploadedPdfBecomesReadyWithChunksThatKeepTheirPages() {
        User owner = newUser();
        long kbId = api.createKnowledgeBase(owner, "PRIVATE");
        byte[] pdf = TestPdfs.withPages(TestPdfs.pageText(1, 1500), TestPdfs.pageText(2, 1500),
                TestPdfs.pageText(3, 600));

        long documentId = upload(owner, kbId, pdf, 202);
        JsonNode ready = api.awaitStatus(documentId, owner, "READY");

        assertThat(ready.path("pageCount").asInt()).isEqualTo(3);
        assertThat(ready.path("embeddingModel").asString()).isEqualTo("text-embedding-v3");
        assertThat(ready.path("task").path("status").asString()).isEqualTo("SUCCEEDED");
        long chunkCount = ready.path("chunkCount").asLong();
        assertThat(chunkCount).isGreaterThanOrEqualTo(3).isEqualTo(chunksOf(documentId));

        // 每个 chunk 只包含它所在页的文本（不跨页），向量维度与列定义一致
        List<String> crossPage = jdbc.queryForList("""
                SELECT chunk_index FROM doc_chunk
                 WHERE document_id = ? AND content NOT LIKE '%page ' || page_number || ' %'""", String.class, documentId);
        assertThat(crossPage).isEmpty();
        assertThat(jdbc.queryForList("SELECT DISTINCT vector_dims(embedding) FROM doc_chunk WHERE document_id = ?",
                Integer.class, documentId)).containsExactly(1024);

        Response chunks = api.get("/api/v1/documents/" + documentId + "/chunks?page=1&size=100", owner);
        assertThat(chunks.data().path("total").asLong()).isEqualTo(chunkCount);
        assertThat(chunks.data().path("items").path(0).path("pageNumber").asInt()).isEqualTo(1);
        // ADR-05：调用 Embedding 时不在事务中
        assertThat(embeddings.calledInsideTransaction()).isFalse();
    }

    @Test
    void uploadingTheSameFileAgainReturnsTheExistingDocument() throws IOException {
        User owner = newUser();
        long kbId = api.createKnowledgeBase(owner, "PRIVATE");
        byte[] pdf = TestPdfs.withPages(TestPdfs.pageText(1, 300));

        long first = upload(owner, kbId, pdf, 202);
        long second = upload(owner, kbId, pdf, 200);

        assertThat(second).isEqualTo(first);
        assertThat(filesOf(kbId)).isEqualTo(1);
    }

    @Test
    void concurrentUploadsOfTheSameFileLeaveOneDocumentAndOneFile() throws Exception {
        User owner = newUser();
        long kbId = api.createKnowledgeBase(owner, "PRIVATE");
        byte[] pdf = TestPdfs.withPages(TestPdfs.pageText(1, 300));
        int uploaders = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(uploaders)) {
            for (int i = 0; i < uploaders; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return api.upload("/api/v1/knowledge-bases/" + kbId + "/documents", owner, "制度.pdf", pdf);
                }));
            }
            start.countDown();
            List<Long> ids = new ArrayList<>();
            for (Future<Response> result : results) {
                Response response = result.get();
                assertThat(response.status()).isIn(200, 202);
                ids.add(response.data().path("id").asLong());
            }
            assertThat(ids).containsOnly(ids.getFirst());
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM document WHERE kb_id = ?", Long.class, kbId)).isEqualTo(1);
        assertThat(filesOf(kbId)).isEqualTo(1);
    }

    @Test
    void nonPdfIsRejectedAndLeavesNoFiles() throws IOException {
        User owner = newUser();
        long kbId = api.createKnowledgeBase(owner, "PRIVATE");

        Response response = api.upload("/api/v1/knowledge-bases/" + kbId + "/documents", owner, "x.pdf",
                "just text, not a pdf".getBytes());

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("INVALID_FILE");
        assertThat(filesOf(kbId)).isZero();
    }

    @Test
    void corruptPdfFailsImmediatelyWithoutRetrying() {
        User owner = newUser();
        long kbId = api.createKnowledgeBase(owner, "PRIVATE");

        long documentId = upload(owner, kbId, "%PDF-1.7 this is not really a pdf".getBytes(), 202);
        JsonNode failed = api.awaitStatus(documentId, owner, "FAILED");

        assertThat(failed.path("errorCode").asString()).isEqualTo("CORRUPT_PDF");
        assertThat(failed.path("task").path("status").asString()).isEqualTo("FAILED");
        assertThat(failed.path("task").path("attempt").asInt()).isEqualTo(1);
        assertThat(embeddings.calls()).isZero();
    }

    @Test
    void deletingADocumentRemovesChunksFileAndRecord() throws IOException {
        User owner = newUser();
        long kbId = api.createKnowledgeBase(owner, "PRIVATE");
        long documentId = upload(owner, kbId, TestPdfs.withPages(TestPdfs.pageText(1, 800)), 202);
        api.awaitStatus(documentId, owner, "READY");

        Response deleting = api.delete("/api/v1/documents/" + documentId, owner);
        assertThat(deleting.status()).isEqualTo(202);
        assertThat(deleting.data().path("status").asString()).isEqualTo("DELETING");
        // 删除事务中已清空 chunk：检索立即看不到
        assertThat(chunksOf(documentId)).isZero();

        api.awaitDocument(documentId, owner, r -> r.status() == 404, Duration.ofSeconds(20));
        assertThat(filesOf(kbId)).isZero();
        // 文档全部删除后，知识库才可以删除
        assertThat(api.delete("/api/v1/knowledge-bases/" + kbId, owner).status()).isEqualTo(200);
    }

    @Test
    void reprocessingRebuildsTheChunks() {
        User owner = newUser();
        long kbId = api.createKnowledgeBase(owner, "PRIVATE");
        long documentId = upload(owner, kbId, TestPdfs.withPages(TestPdfs.pageText(1, 800)), 202);
        long chunkCount = api.awaitStatus(documentId, owner, "READY").path("chunkCount").asLong();

        Response reprocess = api.post("/api/v1/documents/" + documentId + "/reprocess", owner, null);
        assertThat(reprocess.status()).isEqualTo(202);
        assertThat(reprocess.data().path("status").asString()).isEqualTo("PENDING");

        JsonNode ready = api.awaitStatus(documentId, owner, "READY");
        assertThat(ready.path("chunkCount").asLong()).isEqualTo(chunkCount).isEqualTo(chunksOf(documentId));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM async_task WHERE biz_id = ? AND task_type = 'INGEST_DOCUMENT'",
                Long.class, documentId)).isEqualTo(2);
    }

    @Test
    void knowledgeBasePermissionsFollowTheContract() {
        User owner = newUser();
        User reader = newUser();
        User editor = newUser();
        User outsider = newUser();
        long kbId = api.createKnowledgeBase(owner, "PRIVATE");
        String kb = "/api/v1/knowledge-bases/" + kbId;
        assertThat(api.put(kb + "/members/" + reader.id(), owner, "{\"permission\":\"READER\"}").status()).isEqualTo(200);
        assertThat(api.put(kb + "/members/" + editor.id(), owner, "{\"permission\":\"EDITOR\"}").status()).isEqualTo(200);
        byte[] pdf = TestPdfs.withPages(TestPdfs.pageText(1, 300));

        // 非成员：知识库和其中的文档都是 404，不暴露是否存在
        assertThat(api.get(kb, outsider).status()).isEqualTo(404);
        assertThat(api.upload(kb + "/documents", outsider, "a.pdf", pdf).status()).isEqualTo(404);
        // READER：能看，不能上传，不能管理成员
        assertThat(api.get(kb + "/documents", reader).status()).isEqualTo(200);
        assertThat(api.upload(kb + "/documents", reader, "a.pdf", pdf).status()).isEqualTo(403);
        assertThat(api.get(kb + "/members", reader).status()).isEqualTo(403);
        // EDITOR：可以上传；非成员看不到这个文档
        long documentId = upload(editor, kbId, pdf, 202);
        assertThat(api.get("/api/v1/documents/" + documentId, outsider).status()).isEqualTo(404);
        assertThat(api.delete("/api/v1/documents/" + documentId, reader).status()).isEqualTo(403);
        // 列表只返回有权访问的知识库；还有文档时不能删除知识库
        assertThat(api.get("/api/v1/knowledge-bases?size=100", outsider).data().path("items").toString())
                .doesNotContain("\"id\":" + kbId + ",");
        assertThat(api.get(kb + "/members", owner).data().size()).isEqualTo(2);
        assertThat(api.delete(kb, owner).status()).isEqualTo(409);

        // 公开知识库：所有登录用户可以阅读，但不能上传
        String publicKb = "/api/v1/knowledge-bases/" + api.createKnowledgeBase(owner, "PUBLIC");
        assertThat(api.get(publicKb, outsider).status()).isEqualTo(200);
        assertThat(api.get(publicKb, outsider).data().path("myAccess").asString()).isEqualTo("READ");
        assertThat(api.upload(publicKb + "/documents", outsider, "a.pdf", pdf).status()).isEqualTo(403);
    }
}
