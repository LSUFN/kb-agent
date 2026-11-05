package com.example.kbagent.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import com.example.kbagent.chat.service.StaleStreamReaper;
import com.example.kbagent.support.ApiClient;
import com.example.kbagent.support.ApiClient.Response;
import com.example.kbagent.support.ApiClient.SseStream;
import com.example.kbagent.support.ApiClient.User;
import com.example.kbagent.support.ChatFixtures;
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
 * 消息状态机（§4.3）：客户端断开 → INTERRUPTED（§8 M3 验收）、上游失败 → FAILED、心跳、崩溃遗留消息的收尾，
 * 以及会话管理接口。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ChatLifecycleIT {

    private static final String LONG_ANSWER = "这是一段很长的回答，用来模拟模型逐字输出的过程。".repeat(6);

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

    @Autowired
    StaleStreamReaper reaper;

    private ApiClient api;
    private User owner;
    private Kb kb;

    @BeforeEach
    void setUp() {
        api = new ApiClient(port, json);
        embeddings.reset();
        chatModel.reset();
        owner = api.createUserAndLogin(users, passwordEncoder, Role.USER);
        kb = ChatFixtures.readyKnowledgeBase(api, owner, "PRIVATE", 1);
    }

    @AfterEach
    void resetFakes() {
        embeddings.reset();
        chatModel.reset();
    }

    /** §8 M3 验收：断开连接后消息为 INTERRUPTED，已生成的部分被保存，上游请求被取消。 */
    @Test
    void disconnectingInterruptsTheAnswerAndKeepsWhatWasGenerated() {
        long conversationId = ChatFixtures.conversation(api, owner, kb.kbId());
        String question = answerableQuestion();
        chatModel.answer(LONG_ANSWER);
        chatModel.delayEachChunk(Duration.ofMillis(100));

        SseStream partial = api.stream(ChatFixtures.streamPath(conversationId), owner, body(question), 3);

        assertThat(partial.names()).endsWith("delta", "delta", "delta");
        long messageId = partial.first("meta").data().path("messageId").asLong();
        Map<String, Object> saved = awaitMessage(messageId, m -> "INTERRUPTED".equals(m.get("status")));
        String content = (String) saved.get("content");
        assertThat(content).isNotEmpty().startsWith(partial.text());
        assertThat(LONG_ANSWER).startsWith(content);
        assertThat(content.length()).isLessThan(LONG_ANSWER.length());
        assertThat(saved.get("error_code")).isEqualTo("CLIENT_CLOSED");
        assertThat(chatModel.cancelled()).isTrue();

        String traceId = partial.first("meta").data().path("traceId").asString();
        Map<String, Object> chatLog = awaitChatLog(traceId);
        assertThat(chatLog.get("success")).isEqualTo(false);
        assertThat(chatLog.get("error_code")).isEqualTo("CLIENT_CLOSED");
        assertThat(chatLog.get("usage_estimated")).isEqualTo(true);
    }

    @Test
    void upstreamFailureEndsWithAnErrorEventAndKeepsThePartialAnswer() {
        long conversationId = ChatFixtures.conversation(api, owner, kb.kbId());
        chatModel.answer("前两段会先输出，然后上游出错");
        chatModel.failAfter(2, new OpenAIIoException("connection reset by peer"));

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), owner, body(answerableQuestion()));

        assertThat(stream.names()).containsExactly("meta", "retrieval", "delta", "delta", "error");
        JsonNode error = stream.first("error").data();
        String traceId = stream.first("meta").data().path("traceId").asString();
        assertThat(error.path("code").asString()).isEqualTo("UPSTREAM_ERROR");
        assertThat(error.path("message").asString()).isEqualTo("模型服务异常，请稍后重试");
        assertThat(error.path("traceId").asString()).isEqualTo(traceId);

        Map<String, Object> saved = message(stream.first("meta").data().path("messageId").asLong());
        assertThat(saved.get("status")).isEqualTo("FAILED");
        assertThat(saved.get("error_code")).isEqualTo("UPSTREAM_ERROR");
        assertThat(saved.get("content")).isEqualTo(stream.text()).isEqualTo("前两段会先输出，");

        Map<String, Object> chatLog = awaitChatLog(traceId);
        assertThat(chatLog.get("success")).isEqualTo(false);
        assertThat((String) chatLog.get("error_message_masked")).contains("connection reset by peer");
    }

    @Test
    void upstreamTimeoutIsReportedAsTimeout() {
        long conversationId = ChatFixtures.conversation(api, owner, kb.kbId());
        chatModel.failAfter(0, new IllegalStateException("stream failed",
                new SocketTimeoutException("timeout")));

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), owner, body(answerableQuestion()));

        assertThat(stream.names()).containsExactly("meta", "retrieval", "error");
        assertThat(stream.first("error").data().path("code").asString()).isEqualTo("UPSTREAM_TIMEOUT");
        Map<String, Object> saved = message(stream.first("meta").data().path("messageId").asLong());
        assertThat(saved.get("status")).isEqualTo("FAILED");
        assertThat(saved.get("content")).isEqualTo("");
    }

    @Test
    void embeddingFailureDuringRetrievalMarksTheMessageFailed() {
        long conversationId = ChatFixtures.conversation(api, owner, kb.kbId());
        embeddings.failNextCalls(1);

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), owner, body(answerableQuestion()));

        assertThat(stream.names()).containsExactly("meta", "error");
        assertThat(stream.first("error").data().path("code").asString()).isEqualTo("UPSTREAM_ERROR");
        assertThat(message(stream.first("meta").data().path("messageId").asLong()).get("status"))
                .isEqualTo("FAILED");
        assertThat(chatModel.calls()).isZero();
    }

    @Test
    void heartbeatsAreSentWhileWaitingForTheModel() {
        long conversationId = ChatFixtures.conversation(api, owner, kb.kbId());
        chatModel.answer("慢慢地回答[1]");
        chatModel.chunkSize(100);
        chatModel.delayEachChunk(Duration.ofMillis(2500));

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), owner, body(answerableQuestion()));

        assertThat(stream.names()).containsExactly("meta", "retrieval", "delta", "citations", "done");
        assertThat(stream.comments()).isGreaterThanOrEqualTo(1);
        assertThat(stream.first("done").data().path("firstTokenMs").asInt()).isGreaterThanOrEqualTo(2500);
    }

    @Test
    void usageIsEstimatedWhenTheModelDoesNotReportIt() {
        long conversationId = ChatFixtures.conversation(api, owner, kb.kbId());
        chatModel.answer("没有用量的回答[1]");
        chatModel.omitUsage();

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), owner, body(answerableQuestion()));

        JsonNode usage = stream.first("done").data().path("usage");
        assertThat(usage.path("estimated").asBoolean()).isTrue();
        assertThat(usage.path("prompt").asInt()).isPositive();
        assertThat(usage.path("completion").asInt()).isPositive();
    }

    /** 进程崩溃时消息会停在 STREAMING：超过 10 分钟的由扫描器改为 INTERRUPTED，正在输出的不受影响。 */
    @Test
    void staleStreamingMessagesAreInterruptedByTheReaper() {
        long conversationId = ChatFixtures.conversation(api, owner, kb.kbId());
        long stale = insertStreamingMessage(conversationId, "11 minutes");
        long fresh = insertStreamingMessage(conversationId, "1 minute");

        reaper.reap();

        assertThat(message(stale).get("status")).isEqualTo("INTERRUPTED");
        assertThat(message(stale).get("error_code")).isEqualTo("STREAM_TIMEOUT");
        assertThat(message(fresh).get("status")).isEqualTo("STREAMING");
        jdbc.update("UPDATE chat_message SET status = 'INTERRUPTED' WHERE id = ?", fresh);
    }

    @Test
    void conversationsCanBeListedRenamedAndDeleted() {
        User other = api.createUserAndLogin(users, passwordEncoder, Role.USER);
        long first = ChatFixtures.conversation(api, owner, kb.kbId());
        long second = ChatFixtures.conversation(api, owner, null);
        ChatFixtures.conversation(api, other, null);
        api.stream(ChatFixtures.streamPath(first), owner, body("差旅报销" + UUID.randomUUID()));

        // 按更新时间倒序：刚提问的会话排在最前；只返回本人的会话
        JsonNode list = api.get("/api/v1/conversations", owner).data();
        assertThat(list.path("total").asLong()).isEqualTo(2);
        assertThat(list.path("items").get(0).path("id").asLong()).isEqualTo(first);
        assertThat(list.path("items").get(0).path("title").asString()).startsWith("差旅报销");

        Response renamed = api.patch("/api/v1/conversations/" + second, owner, "{\"title\":\"100%_特殊字符\"}");
        assertThat(renamed.data().path("title").asString()).isEqualTo("100%_特殊字符");
        JsonNode search = api.get("/api/v1/conversations?keyword=%25_", owner).data();
        assertThat(search.path("total").asLong()).isEqualTo(1);
        assertThat(search.path("items").get(0).path("id").asLong()).isEqualTo(second);

        assertThat(api.delete("/api/v1/conversations/" + second, owner).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/conversations/" + second, owner).status()).isEqualTo(404);
        assertThat(api.delete("/api/v1/conversations/" + second, owner).status()).isEqualTo(404);
        assertThat(api.stream(ChatFixtures.streamPath(second), owner, body("还能提问吗")).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/conversations", owner).data().path("total").asLong()).isEqualTo(1);

        Response agent = api.post("/api/v1/conversations", owner, "{\"mode\":\"AGENT\"}");
        assertThat(agent.status()).isEqualTo(201);
        assertThat(agent.data().path("mode").asString()).isEqualTo("AGENT");
    }

    @Test
    void messagesArePagedWithACursor() {
        long conversationId = ChatFixtures.conversation(api, owner, kb.kbId());
        for (int i = 1; i <= 3; i++) {
            api.stream(ChatFixtures.streamPath(conversationId), owner, body("第 " + i + " 个问题"));
        }

        JsonNode latest = api.get("/api/v1/conversations/" + conversationId + "/messages?size=4", owner).data();
        List<String> latestContents = contents(latest.path("items"));
        assertThat(latestContents).hasSize(4);
        assertThat(latestContents.get(0)).isEqualTo("第 2 个问题");
        assertThat(latestContents.get(2)).isEqualTo("第 3 个问题");
        long beforeId = latest.path("nextBeforeId").asLong();

        JsonNode older = api.get("/api/v1/conversations/" + conversationId + "/messages?size=4&beforeId=" + beforeId,
                owner).data();
        assertThat(contents(older.path("items"))).hasSize(2).first().isEqualTo("第 1 个问题");
        assertThat(older.path("nextBeforeId").isNull()).isTrue();
    }

    // ---------------------------------------------------------------------

    /** 让问题恰好命中第 1 页的 chunk。 */
    private String answerableQuestion() {
        String question = "报销期限" + UUID.randomUUID();
        embeddings.alias(question, kb.page(1).content());
        return question;
    }

    private String body(String content) {
        return json.writeValueAsString(Map.of("content", content));
    }

    private long insertStreamingMessage(long conversationId, String age) {
        return jdbc.queryForObject("""
                INSERT INTO chat_message(conversation_id, role, content, status, created_at, updated_at)
                VALUES (?, 'ASSISTANT', '', 'STREAMING', now() - CAST(? AS interval), now() - CAST(? AS interval))
                RETURNING id""", Long.class, conversationId, age, age);
    }

    private Map<String, Object> message(long id) {
        return jdbc.queryForMap("SELECT status, content, error_code FROM chat_message WHERE id = ?", id);
    }

    private Map<String, Object> awaitMessage(long id, Predicate<Map<String, Object>> condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Map<String, Object> last;
        do {
            last = message(id);
            if (condition.test(last)) {
                return last;
            }
            sleep();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("message " + id + " did not reach the expected state; last: " + last);
    }

    /** 断开时的调用日志是异步写入的。 */
    private Map<String, Object> awaitChatLog(String traceId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        do {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM llm_call_log WHERE trace_id = ? AND call_type = 'CHAT'", traceId);
            if (!rows.isEmpty()) {
                return rows.getFirst();
            }
            sleep();
        } while (System.nanoTime() < deadline);
        throw new AssertionError("no CHAT call log for trace " + traceId);
    }

    private static List<String> contents(JsonNode items) {
        List<String> contents = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            contents.add(items.get(i).path("content").asString());
        }
        return contents;
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
