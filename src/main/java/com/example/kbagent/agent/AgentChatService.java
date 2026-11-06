package com.example.kbagent.agent;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import com.example.kbagent.business.BusinessProperties;
import com.example.kbagent.chat.domain.ConversationMode;
import com.example.kbagent.chat.domain.ToolStep;
import com.example.kbagent.chat.service.ChatEvent;
import com.example.kbagent.chat.service.ChatModeHandler;
import com.example.kbagent.chat.service.ChatRound;
import com.example.kbagent.chat.service.ChatTransactions;
import com.example.kbagent.chat.service.ChatTransactions.HistoryRound;
import com.example.kbagent.chat.service.ChatTransactions.Turn;
import com.example.kbagent.chat.service.CitationParser;
import com.example.kbagent.chat.service.NumberGrounding;
import com.example.kbagent.knowledge.service.KbPermissionService;
import com.example.kbagent.observability.LlmCallRecorder;
import com.example.kbagent.observability.ToolAuditRecorder.Decision;
import com.example.kbagent.tool.AgentRequestContext;
import com.example.kbagent.tool.CitationRegistry;
import com.example.kbagent.tool.ToolBudget;
import com.example.kbagent.tool.ToolEventSink;
import com.example.kbagent.tool.ToolRegistry;
import com.example.kbagent.user.domain.Actor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/**
 * AGENT 模式（§4.4.1）：使用 Spring AI 自带的工具循环（ToolCallingAdvisor），按角色只暴露有权限的工具，
 * 服务端上下文经 ToolContext 注入；工具调用过程（tool START / END）和模型输出的文本合并到同一个事件流中。
 * <p>
 * 事务边界与 KB_QA 相同（{@link ChatRound}）：本类不开事务，工具里的查询各自使用短的只读事务。
 * 不使用 MessageChatMemoryAdvisor，历史从消息表读取（ADR-11）。
 */
@Service
@EnableConfigurationProperties({AgentProperties.class, BusinessProperties.class})
public class AgentChatService implements ChatModeHandler {

    private final ChatClient chatClient;
    private final ToolRegistry tools;
    private final KbPermissionService permissions;
    private final AgentProperties properties;
    private final BusinessProperties businessProperties;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ChatRound.Dependencies deps;

    public AgentChatService(ChatClient.Builder chatClientBuilder, ToolRegistry tools,
                            KbPermissionService permissions, AgentProperties properties,
                            BusinessProperties businessProperties, Clock clock, ObjectMapper objectMapper,
                            ChatTransactions transactions, LlmCallRecorder recorder,
                            @Value("${spring.ai.openai.chat.options.model:unknown}") String configuredModel) {
        this.chatClient = chatClientBuilder.build();
        this.tools = tools;
        this.permissions = permissions;
        this.properties = properties;
        this.businessProperties = businessProperties;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.deps = new ChatRound.Dependencies(transactions, recorder, configuredModel);
    }

    @Override
    public ConversationMode mode() {
        return ConversationMode.AGENT;
    }

    @Override
    public Flux<ChatEvent> answer(Actor actor, Turn turn, String question, String traceId) {
        return new AgentRound(actor, turn, question, traceId).events();
    }

    private final class AgentRound extends ChatRound {

        /** 本轮 searchKnowledgeBase 的结果，全局编号。 */
        private final CitationRegistry registry = new CitationRegistry();
        /** 本轮工具的参数、返回结果和摘要：数字核对的出处。 */
        private final List<String> evidence = new CopyOnWriteArrayList<>();

        AgentRound(Actor actor, Turn turn, String question, String traceId) {
            super(deps, actor, turn, question, traceId);
        }

        @Override
        protected Flux<ChatEvent> body() {
            // 会话绑定了知识库时只检索它（短事务 1 已校验过读权限），否则是用户有权访问的全部知识库
            return Mono.fromCallable(() -> turn.kbId() != null ? List.of(turn.kbId())
                            : permissions.accessibleKbIds(actor))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(this::generate)
                    .concatWith(finish());
        }

        @Override
        protected CitationResult citations(String answer) {
            return ChatRound.citationsFrom(answer, registry.all());
        }

        /** 回答中的数字必须能在本轮的工具结果中找到出处（M4 实测：模型会不调用工具直接编数字）。 */
        @Override
        protected NumberGrounding.Result checkNumbers(String answer) {
            return NumberGrounding.check(answer, question, evidence);
        }

        /** 本轮没有检索知识库，回答里却标了引用编号：这些内容不是来自知识库。 */
        @Override
        protected String citationNote(CitationResult result) {
            if (!registry.all().isEmpty() || result.invalidIndices().isEmpty()) {
                return null;
            }
            return "\n\n> 引用核对：本轮没有检索知识库，回答中标注的引用编号（"
                    + result.invalidIndices().stream().map(String::valueOf).collect(Collectors.joining("、"))
                    + "）没有对应的资料，相关内容未经知识库核实，请不要采用。";
        }

        /**
         * 模型输出（delta）和工具事件来自不同的线程：工具在 boundedElastic 上执行（S4）。
         * Flux.create 的 sink 是线程安全的，两路事件按发生顺序汇入同一个流；下游取消时连带取消模型请求。
         */
        private Flux<ChatEvent> generate(List<Long> kbIds) {
            List<Message> messages = prompt();
            List<ToolCallback> callbacks = tools.callbacksFor(actor);
            return Flux.create(sink -> {
                ToolEventSink events = new ToolEventSink() {
                    @Override
                    public void started(String callId, String tool, Map<String, Object> args) {
                        sink.next(new ChatEvent.ToolStart(callId, tool, args));
                    }

                    @Override
                    public void finished(String callId, String tool, Map<String, Object> args, Decision decision,
                                         boolean ok, long latencyMs, String summary, String result) {
                        evidence.add(String.valueOf(args));
                        evidence.add(summary == null ? "" : summary);
                        evidence.add(result == null ? "" : result);
                        addToolStep(new ToolStep(callId, tool, args, decision.name(), ok, latencyMs, summary));
                        sink.next(new ChatEvent.ToolEnd(callId, tool, decision.name(), ok, latencyMs, summary));
                    }
                };
                AgentRequestContext context = new AgentRequestContext(actor, kbIds, turn.conversationId(), traceId,
                        new ToolBudget(properties.toolBudget(), properties.maxToolAttempts()), events, registry);
                Disposable upstream = streamModel(messages, prompt -> chatClient.prompt(prompt)
                        .toolCallbacks(callbacks)
                        .toolContext(context.asToolContext())
                        .stream()
                        .chatResponse())
                        .subscribe(sink::next, sink::error, sink::complete);
                sink.onDispose(upstream);
            });
        }

        private List<Message> prompt() {
            LocalDate today = LocalDate.now(clock.withZone(businessProperties.zone()));
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(AgentPrompts.systemPrompt(actor, today, businessProperties.zone())));
            List<HistoryRound> history = turn.history();
            for (int r = 0; r < history.size(); r++) {
                HistoryRound round = history.get(r);
                // 没有调用工具却标了引用编号：内容是编出来的，带进历史会被模型照着重复
                if (round.toolSteps().isEmpty() && !CitationParser.parse(round.answer(), 0).invalid().isEmpty()) {
                    continue;
                }
                messages.add(new UserMessage(round.question()));
                // 历史里保留当时的工具调用（参数已脱敏，结果只有摘要）。只给“问题 + 回答”时，模型会以为助手
                // 是凭记忆直接作答的，追问相关的制度问题时就照着不检索、直接编内容（§4.11）
                if (!round.toolSteps().isEmpty()) {
                    addToolTrace(messages, "h" + r + "-", round.toolSteps());
                }
                // 历史回答里的 [n] 指向当时那一轮的检索结果，带进本轮会和新的编号混淆
                messages.add(new AssistantMessage(CitationParser.stripMarks(round.answer()).strip()));
            }
            messages.add(new UserMessage(question));
            return messages;
        }

        /** 一轮历史中的工具调用：assistant 的 tool_calls 和对应的 tool 消息。id 加上轮次前缀，避免与本轮重复。 */
        private void addToolTrace(List<Message> messages, String idPrefix, List<ToolStep> steps) {
            List<AssistantMessage.ToolCall> calls = steps.stream()
                    .map(s -> new AssistantMessage.ToolCall(idPrefix + s.callId(), "function", s.tool(),
                            objectMapper.writeValueAsString(s.args())))
                    .toList();
            List<ToolResponseMessage.ToolResponse> responses = steps.stream()
                    .map(s -> new ToolResponseMessage.ToolResponse(idPrefix + s.callId(), s.tool(), historyResult(s)))
                    .toList();
            messages.add(AssistantMessage.builder().content("").toolCalls(calls).build());
            messages.add(ToolResponseMessage.builder().responses(responses).build());
        }

        private static String historyResult(ToolStep step) {
            if (!Decision.ALLOWED.name().equals(step.decision())) {
                return "（历史记录）这次调用被拒绝：" + step.decision();
            }
            if (!step.ok()) {
                return "（历史记录）这次调用执行失败";
            }
            return "（历史记录，只保留了结果摘要）" + (step.summary() == null ? "" : step.summary());
        }
    }
}
