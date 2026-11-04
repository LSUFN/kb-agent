package com.example.kbagent.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import com.example.kbagent.storage.StorageProperties;
import com.example.kbagent.support.ApiClient;
import com.example.kbagent.support.ApiClient.User;
import com.example.kbagent.support.TestPdfs;
import com.example.kbagent.support.TestcontainersConfiguration;
import com.example.kbagent.user.domain.Role;
import com.example.kbagent.user.repository.SysUserRepository;
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
 * §7 补偿：在数据库插入阶段注入异常，断言已经移动到正式目录的文件被删除。
 * 用只作用于本测试知识库的触发器制造真实的数据库错误，不需要替换任何 Bean。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class UploadCompensationIT {

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
    StorageProperties storageProperties;

    @Test
    void promotedFileIsDeletedWhenTheDatabaseInsertFails() throws IOException {
        ApiClient api = new ApiClient(port, json);
        User owner = api.createUserAndLogin(users, passwordEncoder, Role.USER);
        long kbId = api.createKnowledgeBase(owner, "PRIVATE");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION it_fail_document_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated database failure'; END $$""");
        jdbc.execute("CREATE TRIGGER it_fail_document_insert BEFORE INSERT ON document FOR EACH ROW WHEN (NEW.kb_id = "
                + kbId + ") EXECUTE FUNCTION it_fail_document_insert()");
        try {
            var response = api.upload("/api/v1/knowledge-bases/" + kbId + "/documents", owner, "a.pdf",
                    TestPdfs.withPages(TestPdfs.pageText(1, 300)));

            assertThat(response.status()).isEqualTo(500);
            assertThat(response.body().toString()).doesNotContain("simulated database failure");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document WHERE kb_id = ?", Long.class, kbId)).isZero();
            // 第 ③ 步移动到正式目录的文件已被补偿删除，临时文件也已清理
            assertThat(count(storageProperties.root().resolve("docs").resolve(Long.toString(kbId)))).isZero();
            assertThat(count(storageProperties.root().resolve("tmp"))).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS it_fail_document_insert ON document");
        }
    }

    private static long count(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }
}
