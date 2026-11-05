package com.example.kbagent.agent;

import static com.example.kbagent.support.FakeChatModel.answerWith;
import static com.example.kbagent.support.FakeChatModel.callTool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.kbagent.business.repository.BizStatsRepository;
import com.example.kbagent.report.service.ReportMarkdown;
import com.example.kbagent.support.ApiClient;
import com.example.kbagent.support.ApiClient.Response;
import com.example.kbagent.support.ApiClient.SseEvent;
import com.example.kbagent.support.ApiClient.SseStream;
import com.example.kbagent.support.ApiClient.User;
import com.example.kbagent.support.ChatFixtures;
import com.example.kbagent.support.ChatFixtures.Kb;
import com.example.kbagent.support.FakeChatModel;
import com.example.kbagent.support.FakeChatModel.Step;
import com.example.kbagent.support.FakeEmbeddingModel;
import com.example.kbagent.support.TestcontainersConfiguration;
import com.example.kbagent.user.domain.Role;
import com.example.kbagent.user.repository.SysUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M4 验收（§8）：SSE 中能看到工具调用的完整过程；越权调用被拒绝、审计为 DENIED、业务仓储没有被调用；
 * 超出调用预算时安全收尾；报告数字与直接执行 SQL 的结果一致；多次检索的引用全局编号。
 * 模型行为由 FakeChatModel 按脚本编排，工具循环、ToolContext 传递和工具执行都是真实的。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AgentIT {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

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

    /** 用来断言越权调用时业务仓储没有被调用（§7）。 */
    @MockitoSpyBean
    BizStatsRepository bizStats;

    private ApiClient api;
    private final LocalDate to = LocalDate.now(ZONE);
    private final LocalDate from = to.minusDays(29);

    @BeforeEach
    void setUp() {
        api = new ApiClient(port, json);
        embeddings.reset();
        chatModel.reset();
    }

    @AfterEach
    void resetFakes() {
        embeddings.reset();
        chatModel.reset();
    }

    @Test
    void streamShowsTheWholeToolCallAndTheModelOnlySeesNumbersFromSql() {
        User analyst = newUser(Role.ANALYST); // 区域范围 EAST
        long conversationId = agentConversation(analyst, null);
        chatModel.script(callTool("queryOrderStats", args("groupBy", "CATEGORY")),
                answerWith("华东最近 30 天的订单按品类统计如上。"));

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), analyst, body("华东最近 30 天各品类的订单情况"));

        assertThat(compact(stream.names())).containsExactly("meta", "tool", "tool", "delta", "citations", "done");
        JsonNode start = stream.events().get(1).data();
        JsonNode end = stream.events().get(2).data();
        assertThat(start.path("phase").asString()).isEqualTo("START");
        assertThat(start.path("name").asString()).isEqualTo("queryOrderStats");
        assertThat(start.path("args").path("groupBy").asString()).isEqualTo("CATEGORY");
        assertThat(start.path("args").has("region")).isFalse();
        assertThat(end.path("phase").asString()).isEqualTo("END");
        assertThat(end.path("callId").asString()).isEqualTo(start.path("callId").asString());
        assertThat(end.path("decision").asString()).isEqualTo("ALLOWED");
        assertThat(end.path("ok").asBoolean()).isTrue();
        assertThat(stream.first("citations").data().path("mode").asString()).isEqualTo("NONE");
        assertThat(stream.first("done").data().path("status").asString()).isEqualTo("OK");

        // 模型拿到的数字等于直接执行 SQL 的结果（区域被收敛为 EAST）
        JsonNode result = json.readTree(toolResponses().getFirst());
        Map<String, Object> expected = orderTotals("EAST");
        assertThat(result.path("regions").get(0).asString()).isEqualTo("EAST");
        assertThat(result.path("total").path("orderCount").asLong()).isEqualTo(expected.get("order_count"));
        assertThat(new BigDecimal(result.path("total").path("paidAmount").asString()))
                .isEqualByComparingTo((BigDecimal) expected.get("paid_amount"));
        assertThat(result.path("rows").size()).isEqualTo(5);

        String traceId = stream.first("meta").data().path("traceId").asString();
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT decision, success, effective_scope::text AS scope, args_masked::text AS args FROM tool_call_log"
                        + " WHERE trace_id = ?", traceId);
        assertThat(audit.get("decision")).isEqualTo("ALLOWED");
        assertThat(audit.get("success")).isEqualTo(true);
        assertThat(audit.get("scope")).isEqualTo("{EAST}");
        assertThat((String) audit.get("args")).contains("\"groupBy\": \"CATEGORY\"");
        assertThat(jdbc.queryForObject("SELECT tool_call_count FROM llm_call_log WHERE trace_id = ? AND call_type = 'CHAT'",
                Integer.class, traceId)).isEqualTo(1);

        // 历史消息里能回看工具步骤
        long messageId = stream.first("meta").data().path("messageId").asLong();
        JsonNode history = api.get("/api/v1/conversations/" + conversationId + "/messages", analyst).data();
        JsonNode steps = history.path("items").get(1).path("toolSteps");
        assertThat(history.path("items").get(1).path("id").asLong()).isEqualTo(messageId);
        assertThat(steps.size()).isEqualTo(1);
        assertThat(steps.get(0).path("tool").asString()).isEqualTo("queryOrderStats");
        assertThat(steps.get(0).path("decision").asString()).isEqualTo("ALLOWED");
    }

    /** 数字核对：模型不调用工具直接给出数字（M4 实测发生过），回答末尾追加提示，done 事件列出这些数字。 */
    @Test
    void numbersWithoutAnyToolCallAreFlagged() {
        User analyst = newUser(Role.ANALYST);
        long conversationId = agentConversation(analyst, null);
        chatModel.script(answerWith("根据查询结果，华北地区上个月共发生异常事件 127 起。"));

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), analyst, body("华北上个月有多少异常事件？"));

        assertThat(stream.first("done").data().path("unverifiedNumbers").get(0).asString()).isEqualTo("127");
        assertThat(stream.text()).startsWith("根据查询结果").contains("本轮没有调用任何查询工具").endsWith("127。");
        long messageId = stream.first("meta").data().path("messageId").asLong();
        assertThat(jdbc.queryForObject("SELECT content FROM chat_message WHERE id = ?", String.class, messageId))
                .isEqualTo(stream.text());
    }

    /** 实测：复用旧会话时，模型照着历史里自己编造的“127 起”原样又说了一遍。含未核实数字的轮次不再作为历史。 */
    @Test
    void roundsWithUnverifiedNumbersAreLeftOutOfTheHistory() {
        User analyst = newUser(Role.ANALYST);
        long conversationId = agentConversation(analyst, null);
        String path = ChatFixtures.streamPath(conversationId);
        chatModel.script(answerWith("华北上个月共发生异常事件 127 起。"), answerWith("华东的订单情况如下。"),
                answerWith("抱歉，你无权查看华北的数据。"));

        api.stream(path, analyst, body("华北上个月有多少异常事件？"));
        api.stream(path, analyst, body("华东的订单情况"));
        api.stream(path, analyst, body("那华北上个月有多少异常事件？"));

        List<Message> prompt = chatModel.lastPrompt().getInstructions();
        assertThat(prompt).extracting(Message::getMessageType).containsExactly(MessageType.SYSTEM,
                MessageType.USER, MessageType.ASSISTANT, MessageType.USER);
        assertThat(prompt.get(2).getText()).isEqualTo("华东的订单情况如下。");
        assertThat(prompt).noneMatch(m -> m.getText() != null && m.getText().contains("127"));

        // 历史消息接口里能看到每条回答的核对结果：编造的那条列出 127，没有数字的那条为空数组
        JsonNode items = api.get("/api/v1/conversations/" + conversationId + "/messages", analyst).data().path("items");
        assertThat(items.get(1).path("unverifiedNumbers").get(0).asString()).isEqualTo("127");
        assertThat(items.get(3).path("unverifiedNumbers").isArray()).isTrue();
        assertThat(items.get(3).path("unverifiedNumbers").size()).isZero();
    }

    @Test
    void numbersTakenFromToolResultsPassTheCheck() {
        User analyst = newUser(Role.ANALYST);
        long conversationId = agentConversation(analyst, null);
        Map<String, Object> total = orderTotals("EAST");
        chatModel.script(callTool("queryOrderStats", args("groupBy", "REGION")),
                answerWith("华东最近 30 天共 " + ReportMarkdown.count((Long) total.get("order_count")) + " 单，成交金额 "
                        + ReportMarkdown.amount((BigDecimal) total.get("paid_amount")) + " 元，同比增长 123.4%。"));

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), analyst, body("华东最近 30 天的订单"));

        // 订单数和金额来自工具结果；增长率无从得出（这里故意编了一个），会被标出
        assertThat(stream.first("done").data().path("unverifiedNumbers").size()).isEqualTo(1);
        assertThat(stream.first("done").data().path("unverifiedNumbers").get(0).asString()).isEqualTo("123.4");
        assertThat(stream.text()).contains("可能是模型计算或推测得出的");
    }

    @Test
    void analystCannotQueryAnotherRegionAndTheRepositoryIsNotCalled() {
        User analyst = newUser(Role.ANALYST);
        long conversationId = agentConversation(analyst, null);
        chatModel.script(callTool("countAnomalies", args("region", "NORTH", "groupBy", "EVENT_TYPE")),
                answerWith("抱歉，你没有权限查看华北区域的数据。"));

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), analyst, body("华北的异常事件有多少"));

        JsonNode end = stream.events().stream().filter(e -> e.name().equals("tool"))
                .map(SseEvent::data).filter(d -> d.path("phase").asString().equals("END")).findFirst().orElseThrow();
        assertThat(end.path("decision").asString()).isEqualTo("DENIED");
        assertThat(end.path("ok").asBoolean()).isFalse();
        assertThat(end.path("summary").asString()).contains("NORTH");
        assertThat(json.readTree(toolResponses().getFirst()).path("error").asString()).isEqualTo("FORBIDDEN_SCOPE");
        assertThat(stream.first("done").data().path("status").asString()).isEqualTo("OK");

        verify(bizStats, never()).anomalyStats(any(), any(), anyInt(), any(), anyInt());
        verify(bizStats, never()).anomalyTotals(any(), any(), anyInt());
        String traceId = stream.first("meta").data().path("traceId").asString();
        assertThat(jdbc.queryForMap("SELECT decision, error_code FROM tool_call_log WHERE trace_id = ?", traceId))
                .containsEntry("decision", "DENIED").containsEntry("error_code", "FORBIDDEN_SCOPE");

        // 管理员能按 decision 查到这次越权尝试；分析员看不到审计日志
        User admin = newUser(Role.ADMIN);
        Response logs = api.get("/api/v1/admin/logs/tool-calls?decision=DENIED&traceId=" + traceId, admin);
        assertThat(logs.data().path("total").asLong()).isEqualTo(1);
        assertThat(logs.data().path("items").get(0).path("toolName").asString()).isEqualTo("countAnomalies");
        assertThat(api.get("/api/v1/admin/logs/tool-calls", analyst).status()).isEqualTo(403);
    }

    @Test
    void userIsNotOfferedDataToolsAndIsDeniedWhenTheModelCallsThemAnyway() {
        User user = newUser(Role.USER);
        long conversationId = agentConversation(user, null);
        chatModel.script(callTool("countAnomalies", args("groupBy", "EVENT_TYPE")),
                answerWith("你没有权限查看异常事件数据。"));

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), user, body("最近有多少异常事件"));

        // 只暴露了检索工具，但模型仍然按名字调用了没有暴露的工具：服务端照样拦住（暴露过滤不是安全边界）
        ToolCallingChatOptions options = (ToolCallingChatOptions) chatModel.prompts().getFirst().getOptions();
        assertThat(options.getToolCallbacks()).extracting(c -> c.getToolDefinition().name())
                .containsExactly("searchKnowledgeBase");
        assertThat(json.readTree(toolResponses().getFirst()).path("error").asString()).isEqualTo("FORBIDDEN_TOOL");
        assertThat(stream.first("done").data().path("status").asString()).isEqualTo("OK");
        verify(bizStats, never()).anomalyStats(any(), any(), anyInt(), any(), anyInt());
        String traceId = stream.first("meta").data().path("traceId").asString();
        assertThat(jdbc.queryForMap("SELECT decision, error_code, user_role FROM tool_call_log WHERE trace_id = ?",
                traceId)).containsEntry("decision", "DENIED").containsEntry("error_code", "FORBIDDEN_TOOL")
                .containsEntry("user_role", "USER");
    }

    @Test
    void afterSixCallsToolsStopExecutingButTheModelStillAnswers() {
        User analyst = newUser(Role.ANALYST);
        long conversationId = agentConversation(analyst, null);
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            steps.add(callTool("queryOrderStats", args("groupBy", "REGION")));
        }
        steps.add(answerWith("基于已有结果作答。"));
        chatModel.script(steps.toArray(Step[]::new));

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), analyst, body("反复查询订单"));

        List<String> decisions = toolEnds(stream).stream().map(d -> d.path("decision").asString()).toList();
        assertThat(decisions).containsExactly("ALLOWED", "ALLOWED", "ALLOWED", "ALLOWED", "ALLOWED", "ALLOWED",
                "BUDGET_EXCEEDED");
        assertThat(json.readTree(toolResponses().getLast()).path("error").asString()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(stream.first("done").data().path("status").asString()).isEqualTo("OK");
        assertThat(stream.text()).isEqualTo("基于已有结果作答。");
        verify(bizStats, times(6)).orderTotals(any());
    }

    @Test
    void tenAttemptsAbortTheRound() {
        User analyst = newUser(Role.ANALYST);
        long conversationId = agentConversation(analyst, null);
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            steps.add(callTool("queryOrderStats", args("groupBy", "REGION")));
        }
        chatModel.script(steps.toArray(Step[]::new));

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), analyst, body("无休止地调用工具"));

        assertThat(stream.names().getLast()).isEqualTo("error");
        assertThat(stream.first("error").data().path("code").asString()).isEqualTo("AGENT_ABORTED");
        assertThat(toolEnds(stream)).hasSize(9);
        long messageId = stream.first("meta").data().path("messageId").asLong();
        Map<String, Object> saved = jdbc.queryForMap(
                "SELECT status, error_code, jsonb_array_length(tool_steps) AS steps FROM chat_message WHERE id = ?",
                messageId);
        assertThat(saved).containsEntry("status", "FAILED").containsEntry("error_code", "AGENT_ABORTED")
                .containsEntry("steps", 9);
        String traceId = stream.first("meta").data().path("traceId").asString();
        assertThat(jdbc.queryForList("SELECT error_code FROM tool_call_log WHERE trace_id = ? ORDER BY id",
                String.class, traceId).getLast()).isEqualTo("AGENT_ABORTED");
    }

    @Test
    void reportNumbersEqualSqlAndOnlyTheOwnerOrAdminCanReadIt() {
        User analyst = newUser(Role.ANALYST);
        long conversationId = agentConversation(analyst, null);
        chatModel.script(callTool("generateReport", args("reportType", "ORDER_SUMMARY")),
                answerWith("报告已生成，可以在报告列表中下载。"));

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), analyst, body("生成华东最近 30 天的订单报告"));

        JsonNode toolResult = json.readTree(toolResponses().getFirst());
        long reportId = toolResult.path("reportId").asLong();
        assertThat(toolEnds(stream).getFirst().path("summary").asString()).contains("#" + reportId);

        Response detail = api.get("/api/v1/reports/" + reportId, analyst);
        assertThat(detail.status()).isEqualTo(200);
        String markdown = detail.data().path("contentMd").asString();
        Map<String, Object> total = orderTotals("EAST");
        assertThat(markdown)
                .contains("- 区域范围：华东（EAST）")
                .contains("| 订单数 | " + ReportMarkdown.count((Long) total.get("order_count")) + " |")
                .contains("| 成交订单数（已支付、已发货） | " + ReportMarkdown.count((Long) total.get("paid_count")) + " |")
                .contains("| 成交金额（元） | " + ReportMarkdown.amount((BigDecimal) total.get("paid_amount")) + " |")
                .contains("| 取消和退款订单数 | " + ReportMarkdown.count((Long) total.get("cancelled_count")) + " |");
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT category, count(*) AS n FROM biz_order
                 WHERE region = 'EAST' AND (created_at AT TIME ZONE 'Asia/Shanghai')::date BETWEEN ? AND ?
                 GROUP BY category""", from, to)) {
            assertThat(markdown).contains("（" + row.get("category") + "） | " + ReportMarkdown.count((Long) row.get("n")) + " |");
        }
        assertThat(jdbc.queryForObject("SELECT params->>'regions' FROM report WHERE id = ?", String.class, reportId))
                .isEqualTo("[\"EAST\"]");

        Response download = api.get("/api/v1/reports/" + reportId + "/download", analyst);
        assertThat(download.status()).isEqualTo(200);
        assertThat(api.get("/api/v1/reports/" + reportId, newUser(Role.ANALYST)).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/reports/" + reportId, newUser(Role.ADMIN)).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/reports", analyst).data().path("items").get(0).path("id").asLong())
                .isEqualTo(reportId);
    }

    @Test
    void citationsAreNumberedAcrossSearches() {
        User user = newUser(Role.USER);
        Kb kb = ChatFixtures.readyKnowledgeBase(api, user, "PRIVATE", 2);
        long conversationId = agentConversation(user, kb.kbId());
        String first = "第一页说了什么" + UUID.randomUUID();
        String second = "第二页说了什么" + UUID.randomUUID();
        embeddings.alias(first, kb.page(1).content());
        embeddings.alias(second, kb.page(2).content());
        chatModel.script(callTool("searchKnowledgeBase", "{\"query\":\"" + first + "\"}"),
                callTool("searchKnowledgeBase", "{\"query\":\"" + second + "\"}"),
                answerWith("第一页的规定[1]，第二页的规定[2]，不存在的[5]。"));

        SseStream stream = api.stream(ChatFixtures.streamPath(conversationId), user, body("两页分别说了什么"));

        // 检索结果是与 KB_QA 相同格式的文本，第二次检索的新片段接着编号
        List<String> responses = toolResponses();
        assertThat(responses.get(0)).contains("[1] 《" + kb.filename() + "》第 1 页").contains("【引用要求】");
        assertThat(responses.get(1)).contains("[2] 《" + kb.filename() + "》第 2 页").doesNotContain("[1] 《");
        JsonNode citations = stream.first("citations").data();
        assertThat(citations.path("mode").asString()).isEqualTo("NORMAL");
        assertThat(citations.path("citations").get(0).path("page").asInt()).isEqualTo(1);
        assertThat(citations.path("citations").get(1).path("page").asInt()).isEqualTo(2);
        assertThat(citations.path("citations").get(1).path("documentId").asLong()).isEqualTo(kb.documentId());
        assertThat(citations.path("invalidIndices").get(0).asInt()).isEqualTo(5);
    }

    // ---------------------------------------------------------------------

    private User newUser(Role role) {
        return api.createUserAndLogin(users, passwordEncoder, role);
    }

    private long agentConversation(User user, Long kbId) {
        Response created = api.post("/api/v1/conversations", user,
                "{\"mode\":\"AGENT\"" + (kbId == null ? "" : ",\"kbId\":" + kbId) + "}");
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        return created.data().path("id").asLong();
    }

    /** 工具参数：最近 30 天，加上额外的键值对。 */
    private String args(String... pairs) {
        StringBuilder args = new StringBuilder("{\"from\":\"" + from + "\",\"to\":\"" + to + "\"");
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            args.append(",\"").append(pairs[i]).append("\":\"").append(pairs[i + 1]).append('"');
        }
        return args.append('}').toString();
    }

    private String body(String content) {
        return json.writeValueAsString(Map.of("content", content));
    }

    /** 最后一次模型调用收到的全部工具结果（按调用顺序）。 */
    private List<String> toolResponses() {
        List<String> responses = new ArrayList<>();
        for (Message message : chatModel.lastPrompt().getInstructions()) {
            if (message instanceof ToolResponseMessage toolResponse) {
                toolResponse.getResponses().forEach(r -> responses.add(r.responseData()));
            }
        }
        return responses;
    }

    private static List<JsonNode> toolEnds(SseStream stream) {
        return stream.events().stream().filter(e -> e.name().equals("tool"))
                .map(SseEvent::data).filter(d -> d.path("phase").asString().equals("END")).toList();
    }

    /** 连续的 delta 合并为一个，便于断言事件顺序。 */
    private static List<String> compact(List<String> names) {
        List<String> compacted = new ArrayList<>();
        for (String name : names) {
            if (!(name.equals("delta") && !compacted.isEmpty() && compacted.getLast().equals("delta"))) {
                compacted.add(name);
            }
        }
        return compacted;
    }

    /** 直接用 SQL 统计 EAST 最近 30 天的订单（与业务代码独立的写法：按业务时区取日期）。 */
    private Map<String, Object> orderTotals(String region) {
        return jdbc.queryForMap("""
                SELECT count(*) AS order_count,
                       count(*) FILTER (WHERE status IN ('PAID', 'SHIPPED')) AS paid_count,
                       coalesce(sum(amount) FILTER (WHERE status IN ('PAID', 'SHIPPED')), 0) AS paid_amount,
                       count(*) FILTER (WHERE status IN ('CANCELLED', 'REFUNDED')) AS cancelled_count
                  FROM biz_order
                 WHERE region = ? AND (created_at AT TIME ZONE 'Asia/Shanghai')::date BETWEEN ? AND ?""",
                region, from, to);
    }
}
