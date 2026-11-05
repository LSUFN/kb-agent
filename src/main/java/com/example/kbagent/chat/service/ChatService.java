package com.example.kbagent.chat.service;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.kbagent.chat.domain.ConversationMode;
import com.example.kbagent.chat.service.ChatTransactions.Turn;
import com.example.kbagent.common.error.RateLimitedException;
import com.example.kbagent.common.ratelimit.RateLimiter;
import com.example.kbagent.user.domain.Actor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 提问入口：限流 → 短事务 1 → 按会话模式交给对应的 {@link ChatModeHandler}。
 * 前两步在调用线程上同步完成，失败直接抛出业务异常（接口返回 4xx）。
 */
@Service
@EnableConfigurationProperties(ChatProperties.class)
public class ChatService {

    private final ChatTransactions transactions;
    private final RateLimiter rateLimiter;
    private final ChatProperties properties;
    private final Map<ConversationMode, ChatModeHandler> handlers = new EnumMap<>(ConversationMode.class);

    public ChatService(ChatTransactions transactions, RateLimiter rateLimiter, ChatProperties properties,
                       List<ChatModeHandler> handlers) {
        this.transactions = transactions;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        for (ChatModeHandler handler : handlers) {
            if (this.handlers.put(handler.mode(), handler) != null) {
                throw new IllegalStateException("duplicate handler for " + handler.mode());
            }
        }
    }

    /** 返回冷的 Flux，订阅后才检索或调用模型；不会以错误结束。 */
    public Flux<ChatEvent> ask(Actor actor, long conversationId, String question, String traceId) {
        RateLimiter.Decision decision = rateLimiter.tryAcquire(
                "chat:" + actor.userId(), properties.rateLimitPerMinute(), Duration.ofMinutes(1));
        if (!decision.allowed()) {
            throw new RateLimitedException(decision.retryAfter());
        }
        Turn turn = transactions.begin(actor, conversationId, question, traceId, properties.historyRounds(),
                Set.copyOf(handlers.keySet()));
        ChatModeHandler handler = handlers.get(turn.mode());
        return Flux.defer(() -> handler.answer(actor, turn, question, traceId));
    }
}
