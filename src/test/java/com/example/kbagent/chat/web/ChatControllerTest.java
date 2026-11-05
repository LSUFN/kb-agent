package com.example.kbagent.chat.web;

import static com.example.kbagent.support.TestActors.USER;
import static com.example.kbagent.support.TestActors.as;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import com.example.kbagent.chat.domain.Citation;
import com.example.kbagent.chat.domain.CitationMode;
import com.example.kbagent.chat.domain.MessageStatus;
import com.example.kbagent.chat.service.ChatEvent;
import com.example.kbagent.chat.service.ChatService;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.RateLimitedException;
import com.example.kbagent.support.SecuredWebMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

@SecuredWebMvcTest(ChatController.class)
class ChatControllerTest {

    private static final String STREAM = "/api/v1/conversations/5/messages/stream";
    private static final String SEND = "/api/v1/conversations/5/messages";
    private static final String BODY = "{\"content\":\"报销期限？\"}";

    private static final List<ChatEvent> ANSWER = List.of(
            new ChatEvent.Meta(11L, 10L, "trace-1"),
            new ChatEvent.Retrieval(List.of(new ChatEvent.RetrievedCandidate(1, 9L, "差旅制度.pdf", 3, 0.82))),
            new ChatEvent.Delta("30 天"),
            new ChatEvent.Delta("[1]"),
            new ChatEvent.Citations(CitationMode.NORMAL,
                    List.of(new Citation(1, 9L, "差旅制度.pdf", 3, "报销须在…", 0.82)), List.of(7)),
            new ChatEvent.Done(MessageStatus.OK, new ChatEvent.Usage(100, 20, false), 1200, 300, null));

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ChatService chat;

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(post(STREAM).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(chat);
    }

    @Test
    void blankOrTooLongQuestionIs400() throws Exception {
        mvc.perform(post(STREAM).with(as(USER)).accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post(SEND).with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + "长".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(chat);
    }

    /** 开始输出之前的错误按普通 JSON 返回，即使请求头是 Accept: text/event-stream。 */
    @Test
    void errorsBeforeStreamingAreJsonEvenForEventStreamRequests() throws Exception {
        when(chat.ask(eq(USER), eq(6L), anyString(), any())).thenThrow(BusinessException.notFound("会话"));
        when(chat.ask(eq(USER), eq(7L), anyString(), any()))
                .thenThrow(new RateLimitedException(Duration.ofSeconds(30)));

        mvc.perform(post("/api/v1/conversations/6/messages/stream").with(as(USER))
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("会话不存在"));
        mvc.perform(post("/api/v1/conversations/7/messages/stream").with(as(USER))
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void streamWritesNamedEventsWithJsonData() throws Exception {
        when(chat.ask(eq(USER), eq(5L), eq("报销期限？"), any())).thenReturn(Flux.fromIterable(ANSWER));

        MvcResult started = mvc.perform(post(STREAM).with(as(USER)).accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/event-stream;charset=UTF-8"))
                .andExpect(content().string(containsString(
                        "event:meta\ndata:{\"messageId\":11,\"userMessageId\":10,\"traceId\":\"trace-1\"}\n\n")))
                .andExpect(content().string(containsString("event:delta\ndata:{\"text\":\"30 天\"}\n\n")))
                .andExpect(content().string(containsString("\"invalidIndices\":[7]")))
                .andExpect(content().string(containsString("event:done\ndata:{\"status\":\"OK\"")));
    }

    @Test
    void nonStreamingEndpointCollectsTheEvents() throws Exception {
        when(chat.ask(eq(USER), eq(5L), eq("报销期限？"), any())).thenReturn(Flux.fromIterable(ANSWER));

        MvcResult started = mvc.perform(post(SEND).with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageId").value(11))
                .andExpect(jsonPath("$.data.content").value("30 天[1]"))
                .andExpect(jsonPath("$.data.citationMode").value("NORMAL"))
                .andExpect(jsonPath("$.data.citations[0].page").value(3))
                .andExpect(jsonPath("$.data.invalidIndices[0]").value(7))
                .andExpect(jsonPath("$.data.candidates[0].filename").value("差旅制度.pdf"))
                .andExpect(jsonPath("$.data.usage.prompt").value(100));
    }

    @Test
    void nonStreamingEndpointTurnsAnErrorEventInto502() throws Exception {
        when(chat.ask(eq(USER), anyLong(), anyString(), any())).thenReturn(Flux.just(
                new ChatEvent.Meta(11L, 10L, "trace-1"),
                new ChatEvent.Error("UPSTREAM_TIMEOUT", "模型服务超时，请稍后重试", "trace-1")));

        MvcResult started = mvc.perform(post(SEND).with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_TIMEOUT"));
    }
}
