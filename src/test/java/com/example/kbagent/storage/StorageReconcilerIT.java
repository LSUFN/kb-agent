package com.example.kbagent.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.example.kbagent.support.ApiClient;
import com.example.kbagent.support.ApiClient.User;
import com.example.kbagent.support.TestPdfs;
import com.example.kbagent.support.TestcontainersConfiguration;
import com.example.kbagent.user.domain.Role;
import com.example.kbagent.user.repository.SysUserRepository;
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
 * §7 补偿（对账部分）：孤儿文件在宽限期后被删除，宽限期内的文件和被引用的文件保留；READY 文档的文件丢失时告警。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class StorageReconcilerIT {

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
    StorageReconciler reconciler;

    @Autowired
    StorageProperties storageProperties;

    private ApiClient api;
    private Path root;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port, json);
        root = storageProperties.root();
    }

    /** 上传一个文档并等到 READY，返回它的文件路径。 */
    private String readyDocumentPath() {
        User owner = api.createUserAndLogin(users, passwordEncoder, Role.USER);
        long kbId = api.createKnowledgeBase(owner, "PRIVATE");
        var response = api.upload("/api/v1/knowledge-bases/" + kbId + "/documents", owner, "a.pdf",
                TestPdfs.withPages(TestPdfs.pageText(1, 300) + UUID.randomUUID()));
        long documentId = response.data().path("id").asLong();
        api.awaitStatus(documentId, owner, "READY");
        return jdbc.queryForObject("SELECT storage_path FROM document WHERE id = ?", String.class, documentId);
    }

    private Path file(String relativePath, Duration age) throws Exception {
        Path path = root.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "%PDF-orphan");
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(age)));
        return path;
    }

    @Test
    void removesStaleTempFilesAndOldOrphansButKeepsRecentAndReferencedFiles() throws Exception {
        String referenced = readyDocumentPath();
        Files.setLastModifiedTime(root.resolve(referenced), FileTime.from(Instant.now().minus(Duration.ofHours(3))));
        String dir = "docs/" + (900_000 + (int) (Math.random() * 1000)) + "/";
        Path oldOrphan = file(dir + "old-orphan.pdf", Duration.ofHours(2));
        Path recentOrphan = file(dir + "recent-orphan.pdf", Duration.ofMinutes(5));
        Path staleTemp = file("tmp/" + UUID.randomUUID() + ".part", Duration.ofHours(2));
        Path freshTemp = file("tmp/" + UUID.randomUUID() + ".part", Duration.ZERO);

        StorageReconciler.Report report = reconciler.reconcile();

        assertThat(oldOrphan).doesNotExist();
        assertThat(staleTemp).doesNotExist();
        // 宽限期内的文件可能正在上传；被文档引用的文件是权威数据
        assertThat(recentOrphan).exists();
        assertThat(freshTemp).exists();
        assertThat(root.resolve(referenced)).exists();
        assertThat(report.orphanFilesDeleted()).isPositive();
        assertThat(report.tempFilesDeleted()).isPositive();
        Files.delete(recentOrphan);
        Files.delete(freshTemp);
    }

    @Test
    void reportsReadyDocumentsWhoseFileIsMissing() throws Exception {
        String path = readyDocumentPath();
        Files.delete(root.resolve(path));

        assertThat(reconciler.reconcile().missingFiles()).isPositive();
    }
}
