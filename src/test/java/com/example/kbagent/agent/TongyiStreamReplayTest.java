package com.example.kbagent.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 通义流式分片回放（§7，M4 验收）：本地模拟服务器按 Spike S2 实测的格式返回工具调用分片——
 * 续传分片带空字符串 {@code "id": ""}（OpenAI 的做法是省略该字段）。Spring AI 2.0.0 会把续传分片当成新的工具调用，
 * 2.0.2 修复了这个问题。这里走真实的 OpenAiChatModel + ChatClient 工具循环，不需要 API Key，
 * 升级 Spring AI 时能第一时间发现回归。
 */
class TongyiStreamReplayTest {

    /** 第一次请求：模型决定调用工具，参数分三片返回。 */
    private static final List<String> TOOL_CALL_CHUNKS = List.of(
            chunk("{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"index\":0,\"id\":\"call_5739abc\","
                    + "\"type\":\"function\",\"function\":{\"name\":\"countOrders\",\"arguments\":\"\"}}]}", null),
            chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"\",\"type\":\"function\","
                    + "\"function\":{\"arguments\":\"{\\\"region\\\": \\\"\"}}]}", null),
            chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"\",\"type\":\"function\","
                    + "\"function\":{\"arguments\":\"EAST\\\"}\"}}]}", null),
            chunk("{}", "tool_calls"),
            usage(120, 18));

    /** 第二次请求（带着工具结果）：模型给出回答。 */
    private static final List<String> ANSWER_CHUNKS = List.of(
            chunk("{\"role\":\"assistant\",\"content\":\"华东共\"}", null),
            chunk("{\"content\":\"有 42 单。\"}", null),
            chunk("{}", "stop"),
            usage(150, 9));

    /** 被调用的工具：记录收到的参数。 */
    static class OrderTools {

        final List<String> calls = new CopyOnWriteArrayList<>();

        @Tool(description = "查询指定区域的订单总数")
        String countOrders(@ToolParam(description = "区域代码") String region) {
            calls.add(region);
            return "{\"region\":\"" + region + "\",\"orderCount\":42}";
        }
    }

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::replay);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void streamingToolCallWithEmptyIdContinuationChunksCompletes() {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                        .apiKey("replay-test")
                        .model("qwen-plus")
                        .maxRetries(0)
                        .timeout(Duration.ofSeconds(10))
                        .build())
                .build();
        OrderTools tools = new OrderTools();

        String answer = String.join("", ChatClient.create(model).prompt()
                .user("华东区域有多少订单？")
                .tools(tools)
                .stream()
                .content()
                .collectList()
                .block(Duration.ofSeconds(20)));

        assertThat(tools.calls).containsExactly("EAST");
        assertThat(answer).isEqualTo("华东共有 42 单。");
        assertThat(requests).hasSize(2);
        // 第二次请求把三片参数正确合并成一次工具调用，并带上了工具结果
        assertThat(requests.get(1)).contains("call_5739abc").contains("\\\"orderCount\\\":42");
    }

    private void replay(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(body);
        List<String> chunks = body.contains("\"role\":\"tool\"") ? ANSWER_CHUNKS : TOOL_CALL_CHUNKS;
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String chunk : chunks) {
                out.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String chunk(String delta, String finishReason) {
        return "{\"id\":\"chatcmpl-replay\",\"object\":\"chat.completion.chunk\",\"created\":1730000000,"
                + "\"model\":\"qwen-plus\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":"
                + (finishReason == null ? "null" : "\"" + finishReason + "\"") + "}]}";
    }

    private static String usage(int prompt, int completion) {
        return "{\"id\":\"chatcmpl-replay\",\"object\":\"chat.completion.chunk\",\"created\":1730000000,"
                + "\"model\":\"qwen-plus\",\"choices\":[],\"usage\":{\"prompt_tokens\":" + prompt
                + ",\"completion_tokens\":" + completion + ",\"total_tokens\":" + (prompt + completion) + "}}";
    }
}
