package com.example.kbagent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.kbagent.support.ApiClient;
import com.example.kbagent.support.ApiClient.Response;
import com.example.kbagent.support.ApiClient.SseStream;
import com.example.kbagent.support.ApiClient.User;
import com.example.kbagent.support.ChatFixtures;
import com.example.kbagent.support.ChatFixtures.Chunk;
import com.example.kbagent.support.ChatFixtures.Kb;
import com.example.kbagent.support.FakeChatModel;
import com.example.kbagent.support.FakeEmbeddingModel;
import com.example.kbagent.support.TestcontainersConfiguration;
import com.example.kbagent.user.domain.Role;
import com.example.kbagent.user.repository.SysUserRepository;
import com.openai.errors.OpenAIIoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M3 KB_QA 端到端（§8 验收）：引用合法性、检索不到时拒答且不调用模型、权限过滤、脱敏、事件协议。
 * 检索结果用 {@link FakeEmbeddingModel#alias} 精确控制：问题的向量 = 若干 chunk 向量之和。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class KbQaIT {

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
    FakeChatModel chatModel;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port, json);
    }

    @BeforeEach
    @AfterEach
    void resetFakes() {
        embeddings.reset();
        chatModel.reset();
    }

    @Test
    void streamsEventsInOrderAndMapsCitationsToTheRetrievedChunks() {
        User owner = newUser(Role.USER);
        Kb kb = readyKnowledgeBase(owner, "PRIVATE", 3);
        long conversationId = conversation(owner, kb.kbId());
        String question = "报销最晚什么时候提交？" + UUID.randomUUID();
        embeddings.alias(question, kb.contents());
        String answer = "出差结束后 30 天内提交[1][3]。另见【2】，[7] 和 [0] 不存在。示例代码 `arr[2]` 不算引用。";
        chatModel.answer(answer);

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), owner, body(question));

        assertThat(stream.status()).isEqualTo(200);
        List<String> names = stream.names();
        assertThat(names.getFirst()).isEqualTo("meta");
        assertThat(names.get(1)).isEqualTo("retrieval");
        assertThat(names.subList(2, names.size() - 2)).isNotEmpty().allMatch("delta"::equals);
        assertThat(names.subList(names.size() - 2, names.size())).containsExactly("citations", "done");
        assertThat(stream.text()).isEqualTo(answer);

        JsonNode meta = stream.first("meta").data();
        long messageId = meta.path("messageId").asLong();
        String traceId = meta.path("traceId").asString();
        assertThat(traceId).isNotBlank();
        assertThat(meta.path("userMessageId").asLong()).isLessThan(messageId);

        // 检索结果：三个 chunk 都达到阈值，编号从 1 开始
        JsonNode candidates = stream.first("retrieval").data().path("candidates");
        assertThat(candidates.size()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(candidates.get(i).path("index").asInt()).isEqualTo(i + 1);
            assertThat(candidates.get(i).path("documentId").asLong()).isEqualTo(kb.documentId());
            assertThat(candidates.get(i).path("score").asDouble()).isGreaterThanOrEqualTo(0.5);
        }

        // 引用合法性（§4.5 层次 A）：合法编号映射到本轮检索到的 chunk，越界编号单独列出，代码里的不算
        JsonNode citations = stream.first("citations").data();
        assertThat(citations.path("mode").asString()).isEqualTo("NORMAL");
        assertThat(ints(citations.path("invalidIndices"))).containsExactly(0, 7);
        JsonNode cited = citations.path("citations");
        assertThat(cited.size()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            JsonNode citation = cited.get(i);
            JsonNode candidate = candidates.get(citation.path("index").asInt() - 1);
            assertThat(citation.path("index").asInt()).isEqualTo(i + 1);
            assertThat(citation.path("documentId").asLong()).isEqualTo(kb.documentId());
            assertThat(citation.path("filename").asString()).isEqualTo(kb.filename());
            assertThat(citation.path("page").asInt()).isEqualTo(candidate.path("page").asInt());
            String chunk = kb.page(citation.path("page").asInt()).content().strip();
            assertThat(chunk).startsWith(citation.path("snippet").asString().replace("…", ""));
        }

        JsonNode done = stream.first("done").data();
        assertThat(done.path("status").asString()).isEqualTo("OK");
        assertThat(done.path("usage").path("prompt").asInt()).isEqualTo(FakeChatModel.PROMPT_TOKENS);
        assertThat(done.path("usage").path("completion").asInt()).isEqualTo(FakeChatModel.COMPLETION_TOKENS);
        assertThat(done.path("usage").path("estimated").asBoolean()).isFalse();
        assertThat(done.path("firstTokenMs").isNull()).isFalse();

        // 收到 done 时短事务 2 已经提交
        Map<String, Object> saved = message(messageId);
        assertThat(saved.get("status")).isEqualTo("OK");
        assertThat(saved.get("content")).isEqualTo(answer);
        assertThat(saved.get("citation_mode")).isEqualTo("NORMAL");
        assertThat(json.readTree((String) saved.get("citations")).size()).isEqualTo(3);

        // 模型收到的 Prompt：System + 带编号资料的用户消息；调用模型和 Embedding 时都不在事务中（ADR-05）
        List<Message> prompt = chatModel.lastPrompt().getInstructions();
        assertThat(prompt).extracting(Message::getMessageType).containsExactly(MessageType.SYSTEM, MessageType.USER);
        assertThat(prompt.getLast().getText()).contains("[1] 《" + kb.filename() + "》第 ", question);
        for (Chunk chunk : kb.chunks()) {
            assertThat(prompt.getLast().getText()).contains(chunk.content().strip());
        }
        assertThat(chatModel.calledInsideTransaction()).isFalse();
        assertThat(embeddings.calledInsideTransaction()).isFalse();

        // 调用日志：一次 Embedding、一次 Chat，都记录了用量和模型
        List<Map<String, Object>> logs = callLogs(traceId);
        assertThat(logs).extracting(r -> r.get("call_type")).containsExactly("EMBEDDING", "CHAT");
        Map<String, Object> chat = logs.get(1);
        assertThat(chat.get("success")).isEqualTo(true);
        assertThat(chat.get("streamed")).isEqualTo(true);
        assertThat(chat.get("chat_mode")).isEqualTo("KB_QA");
        assertThat(chat.get("model")).isEqualTo(FakeChatModel.MODEL);
        assertThat(chat.get("prompt_tokens")).isEqualTo(FakeChatModel.PROMPT_TOKENS);
        assertThat(chat.get("output_chars")).isEqualTo(answer.length());
        assertThat(chat.get("conversation_id")).isEqualTo(conversationId);

        // 历史消息接口返回同样的内容和引用；会话标题取自第一个问题
        Response history = api.get("/api/v1/conversations/" + conversationId + "/messages", owner);
        JsonNode items = history.data().path("items");
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).path("role").asString()).isEqualTo("USER");
        assertThat(items.get(1).path("citations").size()).isEqualTo(3);
        assertThat(items.get(1).path("traceId").asString()).isEqualTo(traceId);
        assertThat(api.get("/api/v1/conversations/" + conversationId, owner).data().path("title").asString())
                .startsWith("报销最晚什么时候提交？");
    }

    @Test
    void answerWithoutCitationsFallsBackToAllRetrievedSources() {
        User owner = newUser(Role.USER);
        Kb kb = readyKnowledgeBase(owner, "PRIVATE", 3);
        long conversationId = conversation(owner, kb.kbId());
        String question = "住宿标准？" + UUID.randomUUID();
        embeddings.alias(question, kb.page(1).content(), kb.page(2).content());
        chatModel.answer("住宿标准按城市分级执行，没有标注来源。");

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), owner, body(question));

        JsonNode citations = stream.first("citations").data();
        assertThat(citations.path("mode").asString()).isEqualTo("FALLBACK");
        assertThat(citations.path("citations").size()).isEqualTo(2);
        assertThat(citations.path("invalidIndices").size()).isZero();
        long messageId = stream.first("meta").data().path("messageId").asLong();
        assertThat(message(messageId).get("citation_mode")).isEqualTo("FALLBACK");
    }

    /** §8 M3 验收：问知识库里没有的内容时拒答，并且不调用模型。 */
    @Test
    void questionsWithoutRelevantSourcesAreRefusedWithoutCallingTheModel() {
        User owner = newUser(Role.USER);
        Kb kb = readyKnowledgeBase(owner, "PRIVATE", 2);
        long conversationId = conversation(owner, kb.kbId());

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), owner, body("公司今年的年会在哪里举办？"));

        assertThat(stream.names()).containsExactly("meta", "retrieval", "delta", "citations", "done");
        assertThat(stream.first("retrieval").data().path("candidates").size()).isZero();
        assertThat(stream.text()).contains("没有找到与这个问题相关的内容");
        assertThat(stream.first("citations").data().path("mode").asString()).isEqualTo("NONE");
        assertThat(stream.first("done").data().path("usage").isNull()).isTrue();
        assertThat(chatModel.calls()).isZero();

        long messageId = stream.first("meta").data().path("messageId").asLong();
        Map<String, Object> saved = message(messageId);
        assertThat(saved.get("status")).isEqualTo("OK");
        assertThat(saved.get("citation_mode")).isEqualTo("NONE");
        String traceId = stream.first("meta").data().path("traceId").asString();
        assertThat(callLogs(traceId)).extracting(r -> r.get("call_type")).containsExactly("EMBEDDING");
    }

    /** §7 权限：用户 A 检索不到 B 的私有知识库内容，即使问题与 B 的片段完全相同。 */
    @Test
    void retrievalOnlyCoversKnowledgeBasesTheUserCanRead() {
        User alice = newUser(Role.USER);
        User bob = newUser(Role.USER);
        Kb bobs = readyKnowledgeBase(bob, "PRIVATE", 1);
        String question = "差旅制度第一页说了什么？" + UUID.randomUUID();
        embeddings.alias(question, bobs.page(1).content());

        long aliceConversation = conversation(alice, null);
        SseStream refused = api.stream(ChatFixtures.streamPath(aliceConversation), alice, body(question));
        assertThat(refused.first("retrieval").data().path("candidates").size()).isZero();

        long bobConversation = conversation(bob, null);
        SseStream answered = api.stream(ChatFixtures.streamPath(bobConversation), bob, body(question));
        JsonNode candidates = answered.first("retrieval").data().path("candidates");
        assertThat(candidates.size()).isEqualTo(1);
        assertThat(candidates.get(0).path("documentId").asLong()).isEqualTo(bobs.documentId());

        // 把 alice 加为成员后就能检索到
        assertThat(api.put("/api/v1/knowledge-bases/" + bobs.kbId() + "/members/" + alice.id(), bob,
                "{\"permission\":\"READER\"}").status()).isEqualTo(200);
        SseStream shared = api.stream(ChatFixtures.streamPath(aliceConversation), alice, body(question));
        assertThat(shared.first("retrieval").data().path("candidates").size()).isEqualTo(1);
    }

    @Test
    void historyFromEarlierRoundsIsSentWithoutCitationMarks() {
        User owner = newUser(Role.USER);
        Kb kb = readyKnowledgeBase(owner, "PRIVATE", 1);
        long conversationId = conversation(owner, kb.kbId());
        String first = "第一个问题" + UUID.randomUUID();
        String second = "第二个问题" + UUID.randomUUID();
        embeddings.alias(first, kb.page(1).content());
        embeddings.alias(second, kb.page(1).content());

        chatModel.answer("第一轮回答[1]。");
        api.stream(ChatFixtures.streamPath(conversationId), owner, body(first));
        chatModel.answer("第二轮回答[1]。");
        api.stream(ChatFixtures.streamPath(conversationId), owner, body(second));

        List<Message> prompt = chatModel.lastPrompt().getInstructions();
        assertThat(prompt).extracting(Message::getMessageType)
                .containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.ASSISTANT, MessageType.USER);
        assertThat(prompt.get(1).getText()).isEqualTo(first);
        assertThat(prompt.get(2).getText()).isEqualTo("第一轮回答。");
        assertThat(prompt.get(3).getText()).contains(second);
    }

    @Test
    void nonStreamingEndpointReturnsTheWholeAnswer() {
        User owner = newUser(Role.USER);
        Kb kb = readyKnowledgeBase(owner, "PRIVATE", 1);
        long conversationId = conversation(owner, kb.kbId());
        String question = "非流式提问" + UUID.randomUUID();
        embeddings.alias(question, kb.page(1).content());
        chatModel.answer("非流式回答[1]。");

        Response reply = api.post("/api/v1/conversations/" + conversationId + "/messages", owner, body(question));

        assertThat(reply.status()).as(reply.body().toString()).isEqualTo(200);
        assertThat(reply.data().path("status").asString()).isEqualTo("OK");
        assertThat(reply.data().path("content").asString()).isEqualTo("非流式回答[1]。");
        assertThat(reply.data().path("citationMode").asString()).isEqualTo("NORMAL");
        assertThat(reply.data().path("citations").get(0).path("page").asInt()).isEqualTo(1);
        assertThat(message(reply.data().path("messageId").asLong()).get("status")).isEqualTo("OK");
    }

    @Test
    void someoneElsesConversationIs404AndNothingIsSaved() {
        User alice = newUser(Role.USER);
        User mallory = newUser(Role.USER);
        Kb kb = readyKnowledgeBase(alice, "PRIVATE", 1);
        long conversationId = conversation(alice, kb.kbId());

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), mallory, body("看看别人的会话"));
        Response nonStreaming = api.post("/api/v1/conversations/" + conversationId + "/messages", mallory,
                body("看看别人的会话"));
        Response created = api.post("/api/v1/conversations", mallory,
                "{\"mode\":\"KB_QA\",\"kbId\":" + kb.kbId() + "}");

        // SSE 请求在开始输出之前失败，也返回普通的 JSON 错误
        assertThat(stream.status()).isEqualTo(404);
        assertThat(stream.errorBody().path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(nonStreaming.status()).isEqualTo(404);
        assertThat(created.status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_message WHERE conversation_id = ?", Long.class,
                conversationId)).isZero();
        assertThat(api.get("/api/v1/conversations/" + conversationId + "/messages", mallory).status())
                .isEqualTo(404);
    }

    /** §8 M3 验收：日志中没有原文敏感信息（调用日志表和应用日志都检查）。 */
    @Test
    void sensitiveDataIsMaskedInCallLogsAndApplicationLogs(CapturedOutput output) throws Exception {
        User owner = newUser(Role.USER);
        Kb kb = readyKnowledgeBase(owner, "PRIVATE", 1);
        long conversationId = conversation(owner, kb.kbId());
        String phone = "13812345678";
        String idCard = "11010519491231002X";
        String email = "zhang.san@example.com";
        String question = "我的手机 " + phone + "，身份证 " + idCard + "，邮箱 " + email + "，报销进度如何？";
        embeddings.alias(question, kb.page(1).content());
        // 模拟上游在报错信息中回显了请求内容
        chatModel.failAfter(1, new OpenAIIoException("upstream rejected input: " + question));

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), owner, body(question));

        assertThat(stream.names().getLast()).isEqualTo("error");
        String traceId = stream.first("meta").data().path("traceId").asString();
        List<Map<String, Object>> logs = callLogs(traceId);
        assertThat(logs).hasSize(2);
        for (Map<String, Object> row : logs) {
            String preview = (String) row.get("input_preview_masked");
            assertThat(preview).doesNotContain(phone, idCard, email).contains("138****5678", "110105********002X");
            assertThat(row.get("input_sha256")).isEqualTo(sha256(question));
        }
        String error = (String) logs.get(1).get("error_message_masked");
        assertThat(error).contains("upstream rejected input").doesNotContain(phone, idCard, email);
        assertThat(output.getAll()).contains(traceId).doesNotContain(phone, idCard, email);

        // 管理员通过接口看到的也是脱敏后的数据
        User admin = newUser(Role.ADMIN);
        Response adminView = api.get("/api/v1/admin/logs/llm-calls?traceId=" + traceId, admin);
        assertThat(adminView.status()).isEqualTo(200);
        assertThat(adminView.data().path("total").asLong()).isEqualTo(2);
        assertThat(adminView.body().toString()).doesNotContain(phone, idCard, email);
        assertThat(api.get("/api/v1/admin/logs/llm-calls?traceId=" + traceId, owner).status()).isEqualTo(403);
    }

    @Test
    void retrievalDebugEndpointShowsScoresAgainstTheThreshold() {
        User owner = newUser(Role.USER);
        User stranger = newUser(Role.USER);
        Kb kb = readyKnowledgeBase(owner, "PRIVATE", 3);
        String query = "检索调试" + UUID.randomUUID();
        embeddings.alias(query, kb.page(2).content());

        Response result = api.post("/api/v1/knowledge-bases/" + kb.kbId() + "/search", owner,
                body("query", query));

        assertThat(result.status()).isEqualTo(200);
        JsonNode hits = result.data().path("hits");
        assertThat(hits.size()).isEqualTo(3);
        assertThat(hits.get(0).path("pageNumber").asInt()).isEqualTo(2);
        assertThat(hits.get(0).path("score").asDouble()).isGreaterThan(0.99);
        assertThat(hits.get(0).path("aboveThreshold").asBoolean()).isTrue();
        assertThat(hits.get(1).path("aboveThreshold").asBoolean()).isFalse();
        assertThat(api.post("/api/v1/knowledge-bases/" + kb.kbId() + "/search", stranger, body("query", query))
                .status()).isEqualTo(404);
    }

    // ---------------------------------------------------------------------

    private User newUser(Role role) {
        return api.createUserAndLogin(users, passwordEncoder, role);
    }

    private Kb readyKnowledgeBase(User owner, String visibility, int pages) {
        return ChatFixtures.readyKnowledgeBase(api, owner, visibility, pages);
    }

    private long conversation(User user, Long kbId) {
        return ChatFixtures.conversation(api, user, kbId);
    }

    private String body(String content) {
        return body("content", content);
    }

    private String body(String field, String value) {
        return json.writeValueAsString(Map.of(field, value));
    }

    private Map<String, Object> message(long id) {
        return jdbc.queryForMap("""
                SELECT status, content, citation_mode, citations::text AS citations, error_code
                  FROM chat_message WHERE id = ?""", id);
    }

    private List<Map<String, Object>> callLogs(String traceId) {
        return jdbc.queryForList("SELECT * FROM llm_call_log WHERE trace_id = ? ORDER BY id", traceId);
    }

    private static List<Integer> ints(JsonNode array) {
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            values.add(array.get(i).asInt());
        }
        return values;
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
