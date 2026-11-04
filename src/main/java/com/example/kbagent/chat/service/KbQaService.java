package com.example.kbagent.chat.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.kbagent.chat.domain.Citation;
import com.example.kbagent.chat.domain.CitationMode;
import com.example.kbagent.chat.domain.ConversationMode;
import com.example.kbagent.chat.domain.MessageStatus;
import com.example.kbagent.chat.service.ChatTransactions.HistoryRound;
import com.example.kbagent.chat.service.ChatTransactions.Turn;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.common.error.RateLimitedException;
import com.example.kbagent.common.error.UpstreamErrors;
import com.example.kbagent.common.ratelimit.RateLimiter;
import com.example.kbagent.observability.LlmCallRecorder;
import com.example.kbagent.retrieval.service.RetrievalService;
import com.example.kbagent.retrieval.service.RetrievalService.CallContext;
import com.example.kbagent.retrieval.service.RetrievalService.Candidate;
import com.example.kbagent.user.domain.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 知识库问答（KB_QA）：检索 → 达到阈值的资料为空时直接拒答（不调用模型）→ 流式生成 → 解析引用 → 收尾。
 * <p>
 * 事务边界（§4.3、ADR-05）：本类不开事务。问题和占位消息在 {@link ChatTransactions#begin} 的短事务中保存，
 * 检索和模型调用期间不持有数据库连接，结束时再用一个短事务写入最终状态。
 * <p>
 * 线程（§6.4、S5）：返回的 Flux 在请求线程之外执行，读不到 SecurityContext，
 * 所以 Actor 和 traceId 由调用方在请求线程上取出后传入。
 */
@Service
@EnableConfigurationProperties(ChatProperties.class)
public class KbQaService {

    private static final Logger log = LoggerFactory.getLogger(KbQaService.class);

    private static final int SNIPPET_LENGTH = 120;
    /** 模型没有返回用量时按字数估算：中文大约 1.5 个字符一个 token。只用于统计。 */
    private static final double CHARS_PER_TOKEN = 1.5;

    private final ChatClient chatClient;
    private final RetrievalService retrieval;
    private final ChatTransactions transactions;
    private final RateLimiter rateLimiter;
    private final LlmCallRecorder recorder;
    private final ChatProperties properties;
    private final String configuredModel;

    public KbQaService(ChatClient.Builder chatClientBuilder, RetrievalService retrieval,
                       ChatTransactions transactions, RateLimiter rateLimiter, LlmCallRecorder recorder,
                       ChatProperties properties,
                       @Value("${spring.ai.openai.chat.options.model:unknown}") String configuredModel) {
        this.chatClient = chatClientBuilder.build();
        this.retrieval = retrieval;
        this.transactions = transactions;
        this.rateLimiter = rateLimiter;
        this.recorder = recorder;
        this.properties = properties;
        this.configuredModel = configuredModel;
    }

    /**
     * 在调用线程上同步完成限流、会话和权限校验、短事务 1；校验失败直接抛出业务异常（接口返回 4xx）。
     * <p>
     * 返回的 Flux 是冷的，订阅后才检索和调用模型；它不会以错误结束，失败会转换为 {@link ChatEvent.Error}。
     * 订阅方取消（客户端断开）时，消息保存已生成的部分并标记为 INTERRUPTED。
     */
    public Flux<ChatEvent> ask(Actor actor, long conversationId, String question, String traceId) {
        RateLimiter.Decision decision = rateLimiter.tryAcquire(
                "chat:" + actor.userId(), properties.rateLimitPerMinute(), Duration.ofMinutes(1));
        if (!decision.allowed()) {
            throw new RateLimitedException(decision.retryAfter());
        }
        Turn turn = transactions.begin(actor, conversationId, question, traceId, properties.historyRounds());
        return Flux.defer(() -> new Round(actor, turn, question, traceId).events());
    }

    /** 一轮问答的执行状态。回调可能在不同线程上执行，可变状态都是线程安全的。 */
    private final class Round {

        private final Actor actor;
        private final Turn turn;
        private final String question;
        private final String traceId;
        private final long startNanos = System.nanoTime();
        /** OK / FAILED / INTERRUPTED 只能写一次：先拿到的一方负责收尾。 */
        private final AtomicBoolean finished = new AtomicBoolean();
        private final StringBuffer answer = new StringBuffer();
        private volatile boolean llmStarted;
        private volatile long llmStartNanos;
        private volatile Integer firstTokenMs;
        private volatile Integer llmFirstTokenMs;
        private volatile Usage usage;
        private volatile String model;
        private volatile int promptChars;

        Round(Actor actor, Turn turn, String question, String traceId) {
            this.actor = actor;
            this.turn = turn;
            this.question = question;
            this.traceId = traceId;
        }

        Flux<ChatEvent> events() {
            Flux<ChatEvent> answering = Mono.fromCallable(this::retrieve)
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(candidates -> Flux.<ChatEvent>concat(
                            Mono.just(ChatEvent.Retrieval.of(candidates)),
                            candidates.isEmpty() ? refuse() : generate(candidates)));
            return Flux.<ChatEvent>concat(
                            Mono.just(new ChatEvent.Meta(turn.answerMessageId(), turn.userMessageId(), traceId)),
                            answering)
                    .onErrorResume(this::fail)
                    .doOnCancel(this::interrupt);
        }

        private List<Candidate> retrieve() {
            return retrieval.retrieve(actor, turn.kbId(), question, new CallContext(traceId, turn.conversationId()));
        }

        /** 没有达到阈值的资料：直接拒答，不调用模型（§8 M3 验收）。 */
        private Flux<ChatEvent> refuse() {
            return Mono.fromCallable(() -> {
                answer.append(KbQaPrompts.REFUSAL);
                if (claim()) {
                    transactions.finishOk(turn.answerMessageId(), KbQaPrompts.REFUSAL, CitationMode.NONE, List.of());
                }
                return List.<ChatEvent>of(
                        new ChatEvent.Delta(KbQaPrompts.REFUSAL),
                        new ChatEvent.Citations(CitationMode.NONE, List.of(), List.of()),
                        new ChatEvent.Done(MessageStatus.OK, null, elapsedMs(startNanos), null));
            }).flatMapIterable(Function.identity());
        }

        private Flux<ChatEvent> generate(List<Candidate> candidates) {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(KbQaPrompts.SYSTEM_PROMPT));
            for (HistoryRound round : turn.history()) {
                messages.add(new UserMessage(round.question()));
                messages.add(new AssistantMessage(KbQaPrompts.historyAnswer(round.answer())));
            }
            messages.add(new UserMessage(KbQaPrompts.userPrompt(question, candidates)));
            promptChars = messages.stream().mapToInt(m -> m.getText() == null ? 0 : m.getText().length()).sum();

            // 直接传入 Message 而不是 .system(text)/.user(text)：文本不经过模板渲染，资料中的 {} 不会被当作占位符
            Flux<ChatEvent> deltas = Flux.defer(() -> {
                        llmStartNanos = System.nanoTime();
                        llmStarted = true;
                        return chatClient.prompt(new Prompt(messages)).stream().chatResponse();
                    })
                    .doOnError(this::recordLlmFailure)
                    .onErrorMap(e -> new BusinessException(UpstreamErrors.classify(e)))
                    .handle((response, sink) -> {
                        String text = observe(response);
                        if (!text.isEmpty()) {
                            sink.next(new ChatEvent.Delta(text));
                        }
                    });
            return deltas.concatWith(Mono.fromCallable(() -> complete(candidates)).flatMapIterable(Function.identity()));
        }

        /** 记录用量、模型名和首字时间，返回本分片的文本。 */
        private String observe(ChatResponse response) {
            ChatResponseMetadata metadata = response.getMetadata();
            if (metadata != null) {
                Usage reported = metadata.getUsage();
                // 流式响应只有最后一个分片带真实用量，其余分片可能是 0
                if (reported != null && (positive(reported.getPromptTokens())
                        || positive(reported.getCompletionTokens()))) {
                    usage = reported;
                }
                if (metadata.getModel() != null && !metadata.getModel().isBlank()) {
                    model = metadata.getModel();
                }
            }
            Generation generation = response.getResult();
            String text = generation == null || generation.getOutput() == null ? null
                    : generation.getOutput().getText();
            if (text == null || text.isEmpty()) {
                return "";
            }
            if (firstTokenMs == null) {
                firstTokenMs = (int) elapsedMs(startNanos);
                llmFirstTokenMs = (int) elapsedMs(llmStartNanos);
            }
            answer.append(text);
            return text;
        }

        /** 模型输出结束：解析引用，写入最终结果。 */
        private List<ChatEvent> complete(List<Candidate> candidates) {
            String content = answer.toString();
            CitationParser.Result parsed = CitationParser.parse(content, candidates.size());
            Map<Integer, Candidate> byIndex = candidates.stream()
                    .collect(Collectors.toMap(Candidate::index, Function.identity()));
            CitationMode mode = parsed.cited().isEmpty() ? CitationMode.FALLBACK : CitationMode.NORMAL;
            // FALLBACK：回答没有标注来源，把本轮检索到的资料全部作为参考资料返回
            List<Citation> citations = (mode == CitationMode.NORMAL
                    ? parsed.cited().stream().map(byIndex::get)
                    : candidates.stream())
                    .map(KbQaService::toCitation)
                    .toList();
            ChatEvent.Usage reportedUsage = usageOf(content);
            recordLlm(content, reportedUsage, null, null);
            if (claim()) {
                transactions.finishOk(turn.answerMessageId(), content, mode, citations);
            }
            return List.of(new ChatEvent.Citations(mode, citations, parsed.invalid()),
                    new ChatEvent.Done(MessageStatus.OK, reportedUsage, elapsedMs(startNanos), firstTokenMs));
        }

        /** 检索失败、模型调用失败或收尾失败：保存已生成的部分，标记为 FAILED，推送 error 事件。 */
        private Mono<ChatEvent> fail(Throwable error) {
            ErrorCode code;
            String message;
            if (error instanceof BusinessException business) {
                code = business.errorCode();
                message = business.getMessage();
            } else {
                code = ErrorCode.INTERNAL_ERROR;
                message = code.defaultMessage();
                log.error("KB_QA round failed (traceId={}, messageId={})", traceId, turn.answerMessageId(), error);
            }
            ChatEvent event = new ChatEvent.Error(code.name(), message, traceId);
            return Mono.fromCallable(() -> {
                if (claim()) {
                    transactions.finishFailed(turn.answerMessageId(), answer.toString(), code.name());
                }
                return event;
            }).onErrorResume(dbError -> {
                log.error("failed to mark message {} as FAILED", turn.answerMessageId(), dbError);
                return Mono.just(event);
            });
        }

        /**
         * 订阅被取消（客户端断开、点击停止生成）。上游的模型请求随取消一起中止；
         * 写库放到 boundedElastic 上执行，不占用触发取消的容器线程。
         */
        private void interrupt() {
            if (!claim()) {
                return;
            }
            String partial = answer.toString();
            boolean wasGenerating = llmStarted;
            log.info("client disconnected, message {} interrupted with {} chars (traceId={})",
                    turn.answerMessageId(), partial.length(), traceId);
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    transactions.finishInterrupted(turn.answerMessageId(), partial);
                } catch (RuntimeException e) {
                    log.error("failed to mark message {} as INTERRUPTED", turn.answerMessageId(), e);
                }
                if (wasGenerating) {
                    recordLlm(partial, usageOf(partial), ChatTransactions.CLIENT_CLOSED, "client disconnected");
                }
            });
        }

        private boolean claim() {
            return finished.compareAndSet(false, true);
        }

        private void recordLlmFailure(Throwable error) {
            ErrorCode code = UpstreamErrors.classify(error);
            // 只记异常类型和（脱敏后的）消息，不打印堆栈：上游报错可能回显请求内容
            log.warn("chat model call failed (traceId={}, code={}): {}", traceId, code, error.toString());
            String partial = answer.toString();
            recordLlm(partial, usageOf(partial), code.name(), error.getMessage());
        }

        private void recordLlm(String output, ChatEvent.Usage callUsage, String errorCode, String errorMessage) {
            LlmCallRecorder.Entry entry = LlmCallRecorder.chat()
                    .traceId(traceId)
                    .userId(actor.userId())
                    .conversationId(turn.conversationId())
                    .chatMode(ConversationMode.KB_QA.name())
                    .model(model != null ? model : configuredModel)
                    .streamed(true)
                    .usage(callUsage.prompt(), callUsage.completion(), callUsage.estimated())
                    .input(question)
                    .outputChars(output.length())
                    .firstTokenMs(llmFirstTokenMs)
                    .latencyMs(elapsedMs(llmStartNanos));
            if (errorCode != null) {
                entry.failed(errorCode, errorMessage);
            }
            recorder.record(entry);
        }

        private ChatEvent.Usage usageOf(String output) {
            Usage reported = usage;
            if (reported != null) {
                return new ChatEvent.Usage(reported.getPromptTokens(), reported.getCompletionTokens(), false);
            }
            return new ChatEvent.Usage(estimateTokens(promptChars), estimateTokens(output.length()), true);
        }
    }

    private static Citation toCitation(Candidate c) {
        return new Citation(c.index(), c.documentId(), c.filename(), c.pageNumber(),
                truncate(c.content().strip(), SNIPPET_LENGTH), Math.round(c.score() * 10_000) / 10_000.0);
    }

    private static String truncate(String text, int maxCodePoints) {
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints)) + "…";
    }

    private static int estimateTokens(int chars) {
        return (int) Math.ceil(chars / CHARS_PER_TOKEN);
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
