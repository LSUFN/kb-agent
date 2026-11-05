package com.example.kbagent.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import com.example.kbagent.chat.domain.CitationMode;
import com.example.kbagent.chat.domain.ConversationMode;
import com.example.kbagent.chat.service.ChatTransactions.Turn;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.observability.LlmCallRecorder;
import com.example.kbagent.retrieval.service.RetrievalService;
import com.example.kbagent.support.TestActors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/** 不涉及模型输出的分支：拒答、检索失败。完整流程见 KbQaIT。 */
class KbQaServiceTest {

    private static final Turn TURN = new Turn(5L, ConversationMode.KB_QA, null, 10L, 11L, List.of());

    private final ChatTransactions transactions = mock(ChatTransactions.class);
    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final LlmCallRecorder recorder = mock(LlmCallRecorder.class);
    private final ChatClient chatClient = mock(ChatClient.class);
    private KbQaService service;

    @BeforeEach
    void setUp() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);
        service = new KbQaService(builder, retrieval, transactions, recorder, "qwen-plus");
    }

    @Test
    void refusesWithoutCallingTheModelWhenNothingIsRetrieved() {
        when(retrieval.retrieve(eq(TestActors.USER), isNull(), eq("报销期限？"), any())).thenReturn(List.of());

        List<ChatEvent> events = service.answer(TestActors.USER, TURN, "报销期限？", "trace-1").collectList().block();

        assertThat(events).extracting(ChatEvent::eventName)
                .containsExactly("meta", "retrieval", "delta", "citations", "done");
        assertThat(((ChatEvent.Delta) events.get(2)).text()).isEqualTo(KbQaPrompts.REFUSAL);
        assertThat(((ChatEvent.Citations) events.get(3)).mode()).isEqualTo(CitationMode.NONE);
        assertThat(((ChatEvent.Done) events.get(4)).usage()).isNull();
        verify(transactions).finishOk(11L, KbQaPrompts.REFUSAL, CitationMode.NONE, List.of(), List.of());
        verifyNoInteractions(chatClient, recorder);
    }

    @Test
    void retrievalFailureEndsWithAnErrorEventAndMarksTheMessageFailed() {
        when(retrieval.retrieve(any(), any(), anyString(), any()))
                .thenThrow(new BusinessException(ErrorCode.UPSTREAM_TIMEOUT));

        List<ChatEvent> events = service.answer(TestActors.USER, TURN, "报销期限？", "trace-1").collectList().block();

        assertThat(events).extracting(ChatEvent::eventName).containsExactly("meta", "error");
        ChatEvent.Error error = (ChatEvent.Error) events.get(1);
        assertThat(error.code()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(error.message()).isEqualTo(ErrorCode.UPSTREAM_TIMEOUT.defaultMessage());
        assertThat(error.traceId()).isEqualTo("trace-1");
        verify(transactions).finishFailed(11L, "", "UPSTREAM_TIMEOUT", List.of());
        verify(transactions, never()).finishOk(anyLong(), any(), any(), any(), any());
        verifyNoInteractions(chatClient);
    }
}
