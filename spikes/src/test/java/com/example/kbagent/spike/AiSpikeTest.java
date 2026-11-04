package com.example.kbagent.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * S2 / S3 / S4：需要真实的通义 API Key（环境变量 AI_API_KEY），未设置时整个类自动跳过。
 * 这些测试会产生少量真实调用费用（十几次短请求）。
 *
 * 断言只覆盖“能否工作”的最低要求；细节（线程、usage 是否为空、异常是否中断循环）
 * 写入 target/spike-report.md，由人判断后回填 ARCHITECTURE.md §2.2。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "AI_API_KEY", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiSpikeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    @Autowired
    ChatClient.Builder chatClientBuilder;

    @Autowired
    EmbeddingModel embeddingModel;

    // ------------------------------------------------------------------ 工具

    /** 正常工具：记录调用线程与 ToolContext 内容。 */
    static class OrderTools {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Tool(description = "查询指定区域的订单总数")
        String countOrders(@ToolParam(description = "区域代码，取值 EAST/NORTH/SOUTH/WEST/CENTRAL") String region,
                           ToolContext toolContext) {
            Map<String, Object> ctx = toolContext == null ? null : toolContext.getContext();
            calls.add("region=" + region + ", thread=" + Thread.currentThread().getName() + ", toolContext=" + ctx);
            return "{\"region\":\"" + region + "\",\"orderCount\":128}";
        }
    }

    /** 不声明 ToolContext 参数的正常工具：单独验证“流式 + 工具调用”本身，不受 ToolContext 影响。 */
    static class PlainOrderTools {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Tool(description = "查询指定区域的订单总数")
        String countOrders(@ToolParam(description = "区域代码，取值 EAST/NORTH/SOUTH/WEST/CENTRAL") String region) {
            calls.add("region=" + region + ", thread=" + Thread.currentThread().getName());
            return "{\"region\":\"" + region + "\",\"orderCount\":128}";
        }
    }

    /** 抛异常的工具：观察异常是返回给模型还是中断整个调用。 */
    static class FailingTools {
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Tool(description = "查询指定区域的订单总数")
        String countOrders(@ToolParam(description = "区域代码") String region) {
            calls.add(region);
            throw new IllegalStateException("SPIKE_ABORT: budget exceeded");
        }
    }

    /** 模拟正式项目的 AgentAbortException：普通 RuntimeException，不是 ToolExecutionException。 */
    static class SpikeAbortException extends RuntimeException {
        SpikeAbortException(String message) {
            super(message);
        }
    }

    /**
     * 模拟 GuardedToolCallback：在 ToolCallback 包装层（而不是 @Tool 方法内部）抛出 SpikeAbortException，
     * 被包装的真实工具不会执行。观察这种异常能否终止整个工具循环。
     */
    static class AbortingToolCallback implements ToolCallback {
        private final ToolCallback delegate;

        AbortingToolCallback(ToolCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return call(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            throw new SpikeAbortException("SPIKE_ABORT: tool budget exhausted");
        }
    }

    private static ToolCallback[] aborting(Object tools) {
        return Arrays.stream(ToolCallbacks.from(tools)).map(AbortingToolCallback::new).toArray(ToolCallback[]::new);
    }

    private static final String TOOL_PROMPT = "华东区域（EAST）有多少订单？必须调用工具查询，不要编造数字。";

    // ------------------------------------------------------------------ S2

    @Test
    @Order(1)
    void s2a_chatCallReturnsUsage() {
        ChatResponse response = chatClientBuilder.build().prompt()
                .user("用一句话介绍 pgvector。")
                .call()
                .chatResponse();
        String text = response.getResult().getOutput().getText();
        Usage usage = response.getMetadata().getUsage();
        SpikeReport.record("S2", "call() 回答长度", text == null ? null : text.length());
        SpikeReport.record("S2", "call() usage prompt/completion",
                usage == null ? null : usage.getPromptTokens() + "/" + usage.getCompletionTokens());
        SpikeReport.record("S2", "call() 返回的 model", response.getMetadata().getModel());
        assertThat(text).isNotBlank();
    }

    @Test
    @Order(2)
    void s2b_streamReturnsUsageAtEnd() {
        List<ChatResponse> chunks = chatClientBuilder.build().prompt()
                .user("用三句话介绍 Spring AI。")
                .stream()
                .chatResponse()
                .collectList()
                .block(TIMEOUT);
        assertThat(chunks).isNotEmpty();
        String usageSeen = chunks.stream()
                .map(c -> c.getMetadata() == null ? null : c.getMetadata().getUsage())
                .filter(u -> u != null && u.getTotalTokens() != null && u.getTotalTokens() > 0)
                .reduce((first, second) -> second)
                .map(u -> u.getPromptTokens() + "/" + u.getCompletionTokens())
                .orElse("<未拿到 usage>");
        SpikeReport.record("S2", "stream() 分片数量", chunks.size());
        SpikeReport.record("S2", "stream() 最后一个非空 usage prompt/completion", usageSeen);
    }

    @Test
    @Order(3)
    void s2c_streamWithToolCalling() {
        PlainOrderTools tools = new PlainOrderTools();
        String answer = String.join("", chatClientBuilder.build().prompt()
                .user(TOOL_PROMPT)
                .tools(tools)
                .stream()
                .content()
                .collectList()
                .block(TIMEOUT));
        SpikeReport.record("S2", "stream() + 工具：工具被调用次数", tools.calls.size());
        SpikeReport.record("S2", "stream() + 工具：最终回答是否包含 128", answer.contains("128"));
        assertThat(tools.calls).isNotEmpty();
    }

    // ------------------------------------------------------------------ S3

    @Test
    @Order(4)
    void s3a_embeddingDimensions() {
        float[] vector = embeddingModel.embed("差旅报销需要在出差结束后 30 天内提交。");
        int expected = Integer.parseInt(System.getenv().getOrDefault("AI_EMBEDDING_DIMENSIONS", "1024"));
        SpikeReport.record("S3", "单条 embedding 维度（期望 " + expected + "）", vector.length);
        SpikeReport.record("S3", "EmbeddingModel.dimensions()", embeddingModel.dimensions());
        assertThat(vector).hasSize(expected);
    }

    @Test
    @Order(5)
    void s3b_embeddingBatchOfTwelve() {
        List<String> texts = IntStream.range(0, 12).mapToObj(i -> "第 " + i + " 段测试文本，用于验证批量上限。").toList();
        try {
            List<float[]> vectors = embeddingModel.embed(texts);
            SpikeReport.record("S3", "一次提交 12 条文本", "成功，返回 " + vectors.size() + " 条");
        } catch (RuntimeException e) {
            SpikeReport.record("S3", "一次提交 12 条文本",
                    "失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ S4

    @Test
    @Order(6)
    void s4a_toolContextInStreamMode() {
        OrderTools tools = new OrderTools();
        chatClientBuilder.build().prompt()
                .user(TOOL_PROMPT)
                .tools(tools)
                .toolContext(Map.of("userId", 42L, "role", "ANALYST"))
                .stream()
                .content()
                .collectList()
                .block(TIMEOUT);
        SpikeReport.record("S4", "stream() 中工具调用记录（线程 / ToolContext）", tools.calls);
        SpikeReport.record("S4", "测试线程", Thread.currentThread().getName());
        assertThat(tools.calls).isNotEmpty();
    }

    @Test
    @Order(7)
    void s4b_toolContextInCallMode() {
        OrderTools tools = new OrderTools();
        chatClientBuilder.build().prompt()
                .user(TOOL_PROMPT)
                .tools(tools)
                .toolContext(Map.of("userId", 42L, "role", "ANALYST"))
                .call()
                .content();
        SpikeReport.record("S4", "call() 中工具调用记录（线程 / ToolContext）", tools.calls);
        SpikeReport.record("S4", "测试线程", Thread.currentThread().getName());
        assertThat(tools.calls).isNotEmpty();
    }

    @Test
    @Order(8)
    void s4c_toolExceptionBehaviour() {
        FailingTools tools = new FailingTools();
        try {
            String answer = chatClientBuilder.build().prompt()
                    .user(TOOL_PROMPT)
                    .tools(tools)
                    .call()
                    .content();
            SpikeReport.record("S4", "工具抛 RuntimeException（call）",
                    "未中断：异常被转成消息交给模型；工具被调用 " + tools.calls.size()
                            + " 次；最终回答前 80 字=" + truncate(answer, 80));
        } catch (RuntimeException e) {
            SpikeReport.record("S4", "工具抛 RuntimeException（call）",
                    "中断：调用方收到 " + e.getClass().getName() + "；工具被调用 " + tools.calls.size() + " 次");
        }
    }

    @Test
    @Order(9)
    void s4d_callbackLevelAbortInCallMode() {
        PlainOrderTools tools = new PlainOrderTools();
        try {
            String answer = chatClientBuilder.build().prompt()
                    .user(TOOL_PROMPT)
                    .toolCallbacks(aborting(tools))
                    .call()
                    .content();
            SpikeReport.record("S4", "包装层抛非 ToolExecutionException（call）",
                    "未中断：最终回答前 80 字=" + truncate(answer, 80));
        } catch (RuntimeException e) {
            SpikeReport.record("S4", "包装层抛非 ToolExecutionException（call）", "中断：" + describe(e)
                    + "；真实工具被调用 " + tools.calls.size() + " 次");
        }
    }

    @Test
    @Order(10)
    void s4e_callbackLevelAbortInStreamMode() {
        PlainOrderTools tools = new PlainOrderTools();
        List<String> received = new CopyOnWriteArrayList<>();
        try {
            chatClientBuilder.build().prompt()
                    .user(TOOL_PROMPT)
                    .toolCallbacks(aborting(tools))
                    .stream()
                    .content()
                    .doOnNext(received::add)
                    .collectList()
                    .block(TIMEOUT);
            SpikeReport.record("S4", "包装层抛非 ToolExecutionException（stream）",
                    "未中断：收到 " + received.size() + " 个分片");
        } catch (RuntimeException e) {
            SpikeReport.record("S4", "包装层抛非 ToolExecutionException（stream）", "中断（Flux onError）："
                    + describe(e) + "；中断前收到 " + received.size() + " 个分片；真实工具被调用 " + tools.calls.size() + " 次");
        }
    }

    @Test
    @Order(11)
    void s4f_toolDeclaresToolContextButNoneProvided() {
        OrderTools tools = new OrderTools();
        try {
            chatClientBuilder.build().prompt()
                    .user(TOOL_PROMPT)
                    .tools(tools)
                    .call()
                    .content();
            SpikeReport.record("S4", "@Tool 声明了 ToolContext 参数但调用方未传 toolContext",
                    "未报错；工具调用记录=" + tools.calls);
        } catch (RuntimeException e) {
            SpikeReport.record("S4", "@Tool 声明了 ToolContext 参数但调用方未传 toolContext",
                    "中断：" + describe(e) + "；工具方法被执行 " + tools.calls.size() + " 次");
        }
    }

    /** 异常类型 + 消息，以及原因链中是否出现 SpikeAbortException。 */
    private static String describe(Throwable e) {
        boolean abortInChain = false;
        for (Throwable t = e; t != null; t = t.getCause()) {
            abortInChain |= t instanceof SpikeAbortException;
        }
        return e.getClass().getName() + "(" + truncate(e.getMessage(), 80) + ")，原因链含 SpikeAbortException=" + abortInChain;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
