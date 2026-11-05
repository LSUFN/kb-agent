package com.example.kbagent.chat.web;

import static com.example.kbagent.support.TestActors.USER;
import static com.example.kbagent.support.TestActors.as;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import com.example.kbagent.chat.domain.ConversationMode;
import com.example.kbagent.chat.service.ConversationCommands.ConversationView;
import com.example.kbagent.chat.service.ConversationCommands.CreateConversation;
import com.example.kbagent.chat.service.ConversationService;
import com.example.kbagent.common.api.CursorPage;
import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.support.SecuredWebMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SecuredWebMvcTest(ConversationController.class)
class ConversationControllerTest {

    private static final ConversationView VIEW = new ConversationView(5L, "新会话", ConversationMode.KB_QA, 9L,
            Instant.now(), Instant.now());

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ConversationService conversations;

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(get("/api/v1/conversations")).andExpect(status().isUnauthorized());
        verifyNoInteractions(conversations);
    }

    @Test
    void createReturns201() throws Exception {
        when(conversations.create(eq(USER), any(CreateConversation.class))).thenReturn(VIEW);

        mvc.perform(post("/api/v1/conversations").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"KB_QA\",\"kbId\":9}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(5))
                .andExpect(jsonPath("$.data.kbId").value(9));
        verify(conversations).create(USER, new CreateConversation(ConversationMode.KB_QA, 9L, null));
    }

    @Test
    void createIsValidated() throws Exception {
        mvc.perform(post("/api/v1/conversations").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kbId\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data[0].field").value("mode"));
        mvc.perform(post("/api/v1/conversations").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"CHAT\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(conversations);
    }

    @Test
    void anyRoleCanCreateAnAgentConversation() throws Exception {
        when(conversations.create(eq(USER), any(CreateConversation.class))).thenReturn(new ConversationView(6L,
                "新会话", ConversationMode.AGENT, null, Instant.now(), Instant.now()));

        mvc.perform(post("/api/v1/conversations").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"AGENT\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.mode").value("AGENT"));
    }

    @Test
    void listPassesKeywordAndPaging() throws Exception {
        when(conversations.list(USER, "差旅", 2, 10)).thenReturn(new PageResponse<>(List.of(VIEW), 2, 10, 11));

        mvc.perform(get("/api/v1/conversations").param("keyword", "差旅").param("page", "2").param("size", "10")
                        .with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(11));
        mvc.perform(get("/api/v1/conversations").param("size", "101").with(as(USER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void someoneElsesConversationIs404() throws Exception {
        when(conversations.get(USER, 6L)).thenThrow(BusinessException.notFound("会话"));
        doThrow(BusinessException.notFound("会话")).when(conversations).delete(USER, 6L);

        mvc.perform(get("/api/v1/conversations/6").with(as(USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("会话不存在"));
        mvc.perform(delete("/api/v1/conversations/6").with(as(USER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void renameIsValidated() throws Exception {
        when(conversations.rename(USER, 5L, "差旅问答")).thenReturn(VIEW);

        mvc.perform(patch("/api/v1/conversations/5").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"差旅问答\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/conversations/5").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void messagesUseCursorPaging() throws Exception {
        when(conversations.messages(USER, 5L, 40L, 20)).thenReturn(new CursorPage<>(List.of(), 21L));

        mvc.perform(get("/api/v1/conversations/5/messages").param("beforeId", "40").param("size", "20")
                        .with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextBeforeId").value(21));
    }
}
