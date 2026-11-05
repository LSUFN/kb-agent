package com.example.kbagent.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import com.example.kbagent.chat.domain.ConversationMode;
import com.example.kbagent.chat.service.ChatTransactions.Turn;
import com.example.kbagent.common.error.RateLimitedException;
import com.example.kbagent.common.ratelimit.RateLimiter;
import com.example.kbagent.support.TestActors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ChatServiceTest {

    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final ChatTransactions transactions = mock(ChatTransactions.class);
    private final ChatModeHandler kbQa = mock(ChatModeHandler.class);
    private final ChatModeHandler agent = mock(ChatModeHandler.class);
    private ChatService service;

    @BeforeEach
    void setUp() {
        when(kbQa.mode()).thenReturn(ConversationMode.KB_QA);
        when(agent.mode()).thenReturn(ConversationMode.AGENT);
        service = new ChatService(transactions, rateLimiter,
                new ChatProperties(20, 3, Duration.ofSeconds(15), Duration.ofMinutes(10)), List.of(kbQa, agent));
    }

    @Test
    void rateLimitedQuestionIsRejectedBeforeAnythingIsSaved() {
        when(rateLimiter.tryAcquire("chat:" + TestActors.USER.userId(), 20, Duration.ofMinutes(1)))
                .thenReturn(new RateLimiter.Decision(false, Duration.ofMillis(11_200)));

        assertThatThrownBy(() -> service.ask(TestActors.USER, 5L, "报销期限？", "trace-1"))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(12));
        verifyNoInteractions(transactions);
    }

    @Test
    void dispatchesToTheHandlerOfTheConversationMode() {
        allow();
        Turn turn = new Turn(5L, ConversationMode.AGENT, null, 10L, 11L, List.of());
        when(transactions.begin(TestActors.ANALYST, 5L, "东区订单", "trace-1", 3,
                Set.of(ConversationMode.KB_QA, ConversationMode.AGENT))).thenReturn(turn);
        when(agent.answer(TestActors.ANALYST, turn, "东区订单", "trace-1"))
                .thenReturn(Flux.just(new ChatEvent.Delta("ok")));

        List<ChatEvent> events = service.ask(TestActors.ANALYST, 5L, "东区订单", "trace-1").collectList().block();

        assertThat(events).containsExactly(new ChatEvent.Delta("ok"));
        verify(agent).answer(eq(TestActors.ANALYST), eq(turn), anyString(), anyString());
        verify(kbQa, never()).answer(any(), any(), any(), any());
    }

    @Test
    void duplicateHandlersAreRejected() {
        assertThatThrownBy(() -> new ChatService(transactions, rateLimiter,
                new ChatProperties(20, 3, Duration.ofSeconds(15), Duration.ofMinutes(10)), List.of(kbQa, kbQa)))
                .isInstanceOf(IllegalStateException.class);
    }

    private void allow() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(
                new RateLimiter.Decision(true, Duration.ZERO));
    }
}
