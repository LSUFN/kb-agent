package com.example.kbagent.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 集成测试用的脚本化 Chat 模型（§7 的 ScriptedChatModel）：按脚本把回答切成若干分片流式返回，
 * 最后一个分片带用量（与通义兼容模式一致，S2）。可以注入分片间延迟和中途失败，并记录收到的 Prompt。
 * 所有集成测试共用一个实例，每个测试前后调用 {@link #reset()}。
 */
public class FakeChatModel implements ChatModel {

    public static final String MODEL = "fake-chat";
    public static final int PROMPT_TOKENS = 321;
    public static final int COMPLETION_TOKENS = 45;
    public static final String DEFAULT_ANSWER = "这是默认回答[1]。";

    private final AtomicInteger calls = new AtomicInteger();
    private final List<Prompt> prompts = new CopyOnWriteArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean calledInsideTransaction = new AtomicBoolean();
    private volatile String answer = DEFAULT_ANSWER;
    private volatile int chunkSize = 4;
    private volatile Duration delayPerChunk = Duration.ZERO;
    private volatile boolean reportUsage = true;
    private volatile RuntimeException failure;
    private volatile int failAfterChunks;

    /** 下一次（及之后）调用返回的完整回答，按 chunkSize 个字符切成分片。 */
    public void answer(String text) {
        this.answer = text;
    }

    public void chunkSize(int size) {
        this.chunkSize = size;
    }

    /** 每个分片之前等待一段时间（在 Reactor 的 parallel 线程上发出，模拟真实的网络流）。 */
    public void delayEachChunk(Duration delay) {
        this.delayPerChunk = delay;
    }

    /** 不返回用量，模拟不支持 stream_options 的服务。 */
    public void omitUsage() {
        this.reportUsage = false;
    }

    /** 输出 n 个分片后以 error 结束。 */
    public void failAfter(int chunks, RuntimeException error) {
        this.failAfterChunks = chunks;
        this.failure = error;
    }

    public int calls() {
        return calls.get();
    }

    public List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    public Prompt lastPrompt() {
        return prompts.getLast();
    }

    /** 下游是否取消了流（客户端断开后应当为 true）。 */
    public boolean cancelled() {
        return cancelled.get();
    }

    public boolean calledInsideTransaction() {
        return calledInsideTransaction.get();
    }

    public void reset() {
        calls.set(0);
        prompts.clear();
        cancelled.set(false);
        calledInsideTransaction.set(false);
        answer = DEFAULT_ANSWER;
        chunkSize = 4;
        delayPerChunk = Duration.ZERO;
        reportUsage = true;
        failure = null;
        failAfterChunks = 0;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        record(prompt);
        if (failure != null) {
            throw failure;
        }
        return response(answer, reportUsage);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            record(prompt);
            List<String> chunks = split(answer, chunkSize);
            RuntimeException error = failure;
            int count = error == null ? chunks.size() : Math.min(failAfterChunks, chunks.size());
            Duration delay = delayPerChunk;
            boolean usage = reportUsage;
            Flux<ChatResponse> body = Flux.range(0, count).concatMap(i -> {
                boolean last = error == null && i == chunks.size() - 1;
                Mono<ChatResponse> chunk = Mono.just(response(chunks.get(i), last && usage));
                return delay.isZero() ? chunk : chunk.delayElement(delay);
            });
            if (error != null) {
                body = body.concatWith(Flux.error(error));
            }
            return body.doOnCancel(() -> cancelled.set(true));
        });
    }

    private void record(Prompt prompt) {
        calls.incrementAndGet();
        prompts.add(prompt);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            calledInsideTransaction.set(true);
        }
    }

    private static ChatResponse response(String text, boolean withUsage) {
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder().model(MODEL);
        if (withUsage) {
            metadata.usage(new DefaultUsage(PROMPT_TOKENS, COMPLETION_TOKENS));
        }
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata.build());
    }

    private static List<String> split(String text, int size) {
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); start += size) {
            chunks.add(text.substring(start, Math.min(text.length(), start + size)));
        }
        return chunks;
    }
}
