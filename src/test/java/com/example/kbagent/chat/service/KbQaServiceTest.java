package com.example.kbagent.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import com.example.kbagent.chat.domain.CitationMode;
import com.example.kbagent.chat.service.ChatTransactions.Turn;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.common.error.RateLimitedException;
import com.example.kbagent.common.ratelimit.RateLimiter;
import com.example.kbagent.observability.LlmCallRecorder;
import com.example.kbagent.retrieval.service.RetrievalService;
import com.example.kbagent.support.TestActors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/** 不涉及模型输出的分支：限流、拒答、检索失败。完整流程见 KbQaIT。 */
class KbQaServiceTest {

    private static final Turn TURN = new Turn(5L, null, 10L, 11L, List.of());

    private final RateLimiter rateLimiter = mock(RateLimiter.class);
    private final ChatTransactions transactions = mock(ChatTransactions.class);
    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final LlmCallRecorder recorder = mock(LlmCallRecorder.class);
    private final ChatClient chatClient = mock(ChatClient.class);
    private KbQaService service;

    @BeforeEach
    void setUp() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        service = new KbQaService(builder, retrieval, transactions, rateLimiter, recorder,
                new ChatProperties(20, 3, Duration.ofSeconds(15), Duration.ofMinutes(10)), "qwen-plus");
    }

    @Test
    void rateLimitedQuestionIsRejectedBeforeAnythingIsSaved() {
        when(rateLimiter.tryAcquire("chat:" + TestActors.USER.userId(), 20, Duration.ofMinutes(1)))
                .thenReturn(new RateLimiter.Decision(false, Duration.ofMillis(11_200)));

        assertThatThrownBy(() -> service.ask(TestActors.USER, 5L, "报销期限？", "trace-1"))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(12));
        verifyNoInteractions(transactions, retrieval, chatClient);
    }

    @Test
    void refusesWithoutCallingTheModelWhenNothingIsRetrieved() {
        allow();
        when(transactions.begin(TestActors.USER, 5L, "报销期限？", "trace-1", 3)).thenReturn(TURN);
        when(retrieval.retrieve(eq(TestActors.USER), isNull(), eq("报销期限？"), any())).thenReturn(List.of());

        List<ChatEvent> events = service.ask(TestActors.USER, 5L, "报销期限？", "trace-1").collectList().block();

        assertThat(events).extracting(ChatEvent::eventName)
                .containsExactly("meta", "retrieval", "delta", "citations", "done");
        assertThat(((ChatEvent.Delta) events.get(2)).text()).isEqualTo(KbQaPrompts.REFUSAL);
        assertThat(((ChatEvent.Citations) events.get(3)).mode()).isEqualTo(CitationMode.NONE);
        assertThat(((ChatEvent.Done) events.get(4)).usage()).isNull();
        verify(transactions).finishOk(11L, KbQaPrompts.REFUSAL, CitationMode.NONE, List.of());
        verifyNoInteractions(chatClient, recorder);
    }

    @Test
    void retrievalFailureEndsWithAnErrorEventAndMarksTheMessageFailed() {
        allow();
        when(transactions.begin(any(), anyLong(), anyString(), anyString(), anyInt())).thenReturn(TURN);
        when(retrieval.retrieve(any(), any(), anyString(), any()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_TIMEOUT));

        List<ChatEvent> events = service.ask(TestActors.USER, 5L, "报销期限？", "trace-1").collectList().block();

        assertThat(events).extracting(ChatEvent::eventName).containsExactly("meta", "error");
        ChatEvent.Error error = (ChatEvent.Error) events.get(1);
        assertThat(error.code()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(error.message()).isEqualTo(ErrorCode.UPSTREAM_TIMEOUT.defaultMessage());
        assertThat(error.traceId()).isEqualTo("trace-1");
        verify(transactions).finishFailed(11L, "", "UPSTREAM_TIMEOUT");
        verify(transactions, never()).finishOk(anyLong(), any(), any(), any());
        verifyNoInteractions(chatClient);
    }

    private void allow() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(
                new RateLimiter.Decision(true, Duration.ZERO));
    }
}
