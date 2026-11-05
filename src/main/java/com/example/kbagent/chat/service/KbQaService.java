package com.example.kbagent.chat.service;

import java.util.ArrayList;
import java.util.List;

import com.example.kbagent.chat.domain.ConversationMode;
import com.example.kbagent.chat.service.ChatTransactions.HistoryRound;
import com.example.kbagent.chat.service.ChatTransactions.Turn;
import com.example.kbagent.observability.LlmCallRecorder;
import com.example.kbagent.retrieval.service.RetrievalService;
import com.example.kbagent.retrieval.service.RetrievalService.CallContext;
import com.example.kbagent.retrieval.service.RetrievalService.Candidate;
import com.example.kbagent.user.domain.Actor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 知识库问答（KB_QA）：检索 → 达到阈值的资料为空时直接拒答（不调用模型）→ 流式生成 → 解析引用 → 收尾。
 * <p>
 * 事务边界（§4.3、ADR-05）：本类不开事务。问题和占位消息已经在 {@link ChatTransactions#begin} 的短事务中保存，
 * 检索和模型调用期间不持有数据库连接，结束时再用一个短事务写入最终状态（见 {@link ChatRound}）。
 * <p>
 * 线程（§6.4、S5）：返回的 Flux 在请求线程之外执行，读不到 SecurityContext，Actor 和 traceId 由调用方传入。
 */
@Service
public class KbQaService implements ChatModeHandler {

    private final ChatClient chatClient;
    private final RetrievalService retrieval;
    private final ChatRound.Dependencies deps;

    public KbQaService(ChatClient.Builder chatClientBuilder, RetrievalService retrieval,
                       ChatTransactions transactions, LlmCallRecorder recorder,
                       @Value("${spring.ai.openai.chat.options.model:unknown}") String configuredModel) {
        this.chatClient = chatClientBuilder.build();
        this.retrieval = retrieval;
        this.deps = new ChatRound.Dependencies(transactions, recorder, configuredModel);
    }

    @Override
    public ConversationMode mode() {
        return ConversationMode.KB_QA;
    }

    @Override
    public Flux<ChatEvent> answer(Actor actor, Turn turn, String question, String traceId) {
        return new KbQaRound(actor, turn, question, traceId).events();
    }

    private final class KbQaRound extends ChatRound {

        /** 本轮检索到的资料；检索完成后才有值。 */
        private volatile List<Candidate> candidates = List.of();

        KbQaRound(Actor actor, Turn turn, String question, String traceId) {
            super(deps, actor, turn, question, traceId);
        }

        @Override
        protected Flux<ChatEvent> body() {
            return Mono.fromCallable(() -> retrieval.retrieve(actor, turn.kbId(), question,
                            new CallContext(traceId, turn.conversationId())))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(found -> {
                        candidates = found;
                        return Flux.<ChatEvent>concat(
                                Mono.just(ChatEvent.Retrieval.of(found)),
                                found.isEmpty()
                                        // 没有达到阈值的资料：直接拒答，不调用模型（§8 M3 验收）
                                        ? answerDirectly(KbQaPrompts.REFUSAL, CitationResult.none())
                                        : streamModel(prompt(found), p -> chatClient.prompt(p).stream().chatResponse())
                                                .concatWith(finish()));
                    });
        }

        @Override
        protected CitationResult citations(String answer) {
            return ChatRound.citationsFrom(answer, candidates);
        }

        /** 直接传入 Message 而不是 .system(text)/.user(text)：文本不经过模板渲染，资料中的 {} 不会被当作占位符。 */
        private List<Message> prompt(List<Candidate> found) {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(KbQaPrompts.SYSTEM_PROMPT));
            for (HistoryRound round : turn.history()) {
                messages.add(new UserMessage(round.question()));
                messages.add(new AssistantMessage(KbQaPrompts.historyAnswer(round.answer())));
            }
            messages.add(new UserMessage(KbQaPrompts.userPrompt(question, found)));
            return messages;
        }
    }
}
