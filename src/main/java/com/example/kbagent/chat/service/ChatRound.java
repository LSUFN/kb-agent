package com.example.kbagent.chat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import com.example.kbagent.chat.domain.Citation;
import com.example.kbagent.chat.domain.CitationMode;
import com.example.kbagent.chat.domain.ConversationMode;
import com.example.kbagent.chat.domain.MessageStatus;
import com.example.kbagent.chat.domain.ToolStep;
import com.example.kbagent.chat.service.ChatTransactions.Turn;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.common.error.UpstreamErrors;
import com.example.kbagent.observability.LlmCallRecorder;
import com.example.kbagent.retrieval.service.RetrievalService.Candidate;
import com.example.kbagent.user.domain.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 一轮问答中与模式无关的部分（模板方法）：meta 事件、首字时间、用量、累计输出、工具步骤、
 * 三种结束方式（完成 / 失败 / 被取消）的互斥收尾、调用日志和错误转换。子类只负责产生回答（{@link #body()}）
 * 和计算引用（{@link #citations(String)}）。
 * <p>
 * 本类不开事务（ADR-05）；回调可能在不同线程上执行，可变状态都是线程安全的。
 */
public abstract class ChatRound {

    private static final Logger log = LoggerFactory.getLogger(ChatRound.class);

    private static final int SNIPPET_LENGTH = 120;
    /** 模型没有返回用量时按字数估算：中文大约 1.5 个字符一个 token。只用于统计。 */
    private static final double CHARS_PER_TOKEN = 1.5;

    /** 各模式共用的依赖。 */
    public record Dependencies(ChatTransactions transactions, LlmCallRecorder recorder, String configuredModel) {
    }

    /** 模型输出结束后的引用情况。 */
    public record CitationResult(CitationMode mode, List<Citation> citations, List<Integer> invalidIndices) {

        public static CitationResult none() {
            return new CitationResult(CitationMode.NONE, List.of(), List.of());
        }
    }

    protected final Actor actor;
    protected final Turn turn;
    protected final String question;
    protected final String traceId;
    private final Dependencies deps;
    private final long startNanos = System.nanoTime();
    /** OK / FAILED / INTERRUPTED 只能写一次：先拿到的一方负责收尾。 */
    private final AtomicBoolean finished = new AtomicBoolean();
    private final StringBuffer answer = new StringBuffer();
    private final List<ToolStep> toolSteps = new CopyOnWriteArrayList<>();
    private volatile boolean llmStarted;
    private volatile long llmStartNanos;
    private volatile Integer firstTokenMs;
    private volatile Integer llmFirstTokenMs;
    private volatile Usage usage;
    private volatile String model;
    private volatile int promptChars;

    protected ChatRound(Dependencies deps, Actor actor, Turn turn, String question, String traceId) {
        this.deps = deps;
        this.actor = actor;
        this.turn = turn;
        this.question = question;
        this.traceId = traceId;
    }

    /** 冷的事件流：meta → {@link #body()}。不会以错误结束；被取消时标记 INTERRUPTED。 */
    public final Flux<ChatEvent> events() {
        return Flux.<ChatEvent>concat(
                        Mono.just(new ChatEvent.Meta(turn.answerMessageId(), turn.userMessageId(), traceId)),
                        Flux.defer(this::body))
                .onErrorResume(this::fail)
                .doOnCancel(this::interrupt);
    }

    /** 产生回答：通常是 {@link #streamModel} 之后接 {@link #finish()}。可以抛出 BusinessException。 */
    protected abstract Flux<ChatEvent> body();

    /** 模型输出结束后，根据本轮的资料解析引用。 */
    protected abstract CitationResult citations(String answer);

    /** 模型输出结束后的数字核对；返回 null 表示这种模式不做核对。 */
    protected NumberGrounding.Result checkNumbers(String answer) {
        return null;
    }

    /** 模型输出结束后的引用核对：需要提醒用户时返回追加在回答末尾的说明，否则返回 null。 */
    protected String citationNote(CitationResult result) {
        return null;
    }

    /**
     * 流式调用模型：文本分片转为 delta 事件，同时记录首字时间、用量和模型名。
     * 失败时写调用日志，并把异常转换为 BusinessException（上游错误归类为 UPSTREAM_*，其他保持原样）。
     */
    protected final Flux<ChatEvent> streamModel(List<Message> messages, Function<Prompt, Flux<ChatResponse>> call) {
        promptChars = messages.stream().mapToInt(m -> m.getText() == null ? 0 : m.getText().length()).sum();
        return Flux.defer(() -> {
                    llmStartNanos = System.nanoTime();
                    llmStarted = true;
                    return call.apply(new Prompt(messages));
                })
                .doOnError(this::recordLlmFailure)
                .onErrorMap(ChatRound::toBusinessException)
                .handle((response, sink) -> {
                    String text = observe(response);
                    if (!text.isEmpty()) {
                        sink.next(new ChatEvent.Delta(text));
                    }
                });
    }

    /**
     * 模型输出结束：解析引用、数字核对、引用核对、写调用日志、执行短事务 2，然后推送 citations 和 done。
     * 核对发现问题时，在回答末尾追加提示（同时推送一个 delta 并写入消息），客户端不认识新字段也能看到。
     * 引用在追加提示之前解析，提示里的文字不会被当成引用。
     */
    protected final Flux<ChatEvent> finish() {
        return Mono.fromCallable(() -> {
            String modelOutput = answer.toString();
            CitationResult result = citations(modelOutput);
            NumberGrounding.Result check = checkNumbers(modelOutput);
            String numberNote = NumberGrounding.note(check);
            if (numberNote != null) {
                log.warn("unverified numbers in {} answer (traceId={}, messageId={}): {}", turn.mode(), traceId,
                        turn.answerMessageId(), check.unverified());
            }
            String citationNote = citationNote(result);
            if (citationNote != null) {
                log.warn("citations without sources in {} answer (traceId={}, messageId={}): {}", turn.mode(),
                        traceId, turn.answerMessageId(), result.invalidIndices());
            }
            String note = numberNote == null ? citationNote
                    : citationNote == null ? numberNote : numberNote + citationNote;
            if (note != null) {
                answer.append(note);
            }
            String content = answer.toString();
            ChatEvent.Usage reportedUsage = usageOf(modelOutput);
            recordLlm(modelOutput, reportedUsage, null, null);
            if (claim()) {
                deps.transactions().finishOk(turn.answerMessageId(), content, result.mode(), result.citations(),
                        List.copyOf(toolSteps), check == null ? null : check.unverified());
            }
            List<ChatEvent> events = new ArrayList<>();
            if (note != null) {
                events.add(new ChatEvent.Delta(note));
            }
            events.add(new ChatEvent.Citations(result.mode(), result.citations(), result.invalidIndices()));
            events.add(new ChatEvent.Done(MessageStatus.OK, reportedUsage, elapsedMs(startNanos), firstTokenMs,
                    check == null ? null : check.unverified()));
            return events;
        }).flatMapIterable(Function.identity());
    }

    /** 不调用模型，直接给出固定的回答（拒答）。 */
    protected final Flux<ChatEvent> answerDirectly(String text, CitationResult result) {
        return Mono.fromCallable(() -> {
            answer.append(text);
            if (claim()) {
                deps.transactions().finishOk(turn.answerMessageId(), text, result.mode(), result.citations(),
                        List.copyOf(toolSteps), null);
            }
            return List.<ChatEvent>of(new ChatEvent.Delta(text),
                    new ChatEvent.Citations(result.mode(), result.citations(), result.invalidIndices()),
                    new ChatEvent.Done(MessageStatus.OK, null, elapsedMs(startNanos), null, null));
        }).flatMapIterable(Function.identity());
    }

    /** 记录一次工具调用（AGENT 模式），收尾时一起写入消息。 */
    protected final void addToolStep(ToolStep step) {
        toolSteps.add(step);
    }

    /** 把检索到的片段转成引用；snippet 是片段开头的一小段原文。 */
    public static Citation toCitation(Candidate c) {
        return new Citation(c.index(), c.documentId(), c.filename(), c.pageNumber(),
                truncate(c.content().strip(), SNIPPET_LENGTH), Math.round(c.score() * 10_000) / 10_000.0);
    }

    /** 根据回答中的编号和本轮资料计算引用：有合法引用为 NORMAL，有资料但没有合法引用为 FALLBACK，没有资料为 NONE。 */
    public static CitationResult citationsFrom(String answer, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            CitationParser.Result parsed = CitationParser.parse(answer, 0);
            return new CitationResult(CitationMode.NONE, List.of(), parsed.invalid());
        }
        int maxIndex = candidates.stream().mapToInt(Candidate::index).max().orElse(0);
        CitationParser.Result parsed = CitationParser.parse(answer, maxIndex);
        List<Citation> cited = new ArrayList<>();
        List<Integer> invalid = new ArrayList<>(parsed.invalid());
        for (int index : parsed.cited()) {
            candidates.stream().filter(c -> c.index() == index).findFirst()
                    .ifPresentOrElse(c -> cited.add(toCitation(c)), () -> invalid.add(index));
        }
        if (cited.isEmpty()) {
            return new CitationResult(CitationMode.FALLBACK, candidates.stream().map(ChatRound::toCitation).toList(),
                    invalid.stream().sorted().toList());
        }
        return new CitationResult(CitationMode.NORMAL, cited, invalid.stream().sorted().toList());
    }

    /** 记录用量、模型名和首字时间，返回本分片的文本。 */
    private String observe(ChatResponse response) {
        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata != null) {
            Usage reported = metadata.getUsage();
            // 流式响应只有最后一个分片带真实用量，其余分片可能是 0；工具循环的用量由框架累计
            if (reported != null && (positive(reported.getPromptTokens())
                    || positive(reported.getCompletionTokens()))) {
                usage = reported;
            }
            if (metadata.getModel() != null && !metadata.getModel().isBlank()) {
                model = metadata.getModel();
            }
        }
        Generation generation = response.getResult();
        String text = generation == null || generation.getOutput() == null ? null : generation.getOutput().getText();
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
            log.error("{} round failed (traceId={}, messageId={})", turn.mode(), traceId, turn.answerMessageId(),
                    error);
        }
        ChatEvent event = new ChatEvent.Error(code.name(), message, traceId);
        return Mono.fromCallable(() -> {
            if (claim()) {
                deps.transactions().finishFailed(turn.answerMessageId(), answer.toString(), code.name(),
                        List.copyOf(toolSteps));
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
        List<ToolStep> steps = List.copyOf(toolSteps);
        boolean wasGenerating = llmStarted;
        log.info("client disconnected, message {} interrupted with {} chars (traceId={})",
                turn.answerMessageId(), partial.length(), traceId);
        Schedulers.boundedElastic().schedule(() -> {
            try {
                deps.transactions().finishInterrupted(turn.answerMessageId(), partial, steps);
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
        BusinessException business = businessCause(error);
        String code = business != null ? business.errorCode().name() : UpstreamErrors.classify(error).name();
        // 只记异常类型和（脱敏后的）消息，不打印堆栈：上游报错可能回显请求内容
        log.warn("chat model call failed (traceId={}, code={}): {}", traceId, code, error.toString());
        String partial = answer.toString();
        recordLlm(partial, usageOf(partial), code, error.getMessage());
    }

    private void recordLlm(String output, ChatEvent.Usage callUsage, String errorCode, String errorMessage) {
        LlmCallRecorder.Entry entry = LlmCallRecorder.chat()
                .traceId(traceId)
                .userId(actor.userId())
                .conversationId(turn.conversationId())
                .chatMode(turn.mode().name())
                .model(model != null ? model : deps.configuredModel())
                .streamed(true)
                .usage(callUsage.prompt(), callUsage.completion(), callUsage.estimated())
                .toolCallCount(toolSteps.size())
                .input(question)
                .outputChars(output.length())
                .firstTokenMs(llmFirstTokenMs)
                .latencyMs(elapsedMs(llmStartNanos));
        if (errorCode != null) {
            entry.failed(errorCode, errorMessage);
        }
        deps.recorder().record(entry);
    }

    private ChatEvent.Usage usageOf(String output) {
        Usage reported = usage;
        if (reported != null) {
            return new ChatEvent.Usage(reported.getPromptTokens(), reported.getCompletionTokens(), false);
        }
        return new ChatEvent.Usage(estimateTokens(promptChars), estimateTokens(output.length()), true);
    }

    /** 业务异常（例如工具层的 AgentAbortException）保持原样，其他异常按上游错误归类。 */
    private static Throwable toBusinessException(Throwable error) {
        BusinessException business = businessCause(error);
        return business != null ? business : new BusinessException(UpstreamErrors.classify(error));
    }

    private static BusinessException businessCause(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof BusinessException business) {
                return business;
            }
        }
        return null;
    }

    protected final ConversationMode mode() {
        return turn.mode();
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
