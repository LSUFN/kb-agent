package com.example.kbagent.knowledge.web;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.knowledge.domain.KbAccess;
import com.example.kbagent.knowledge.domain.KbPermission;
import com.example.kbagent.knowledge.domain.Visibility;
import com.example.kbagent.knowledge.service.KnowledgeBaseCommands.CreateKnowledgeBase;
import com.example.kbagent.knowledge.service.KnowledgeBaseCommands.KnowledgeBaseView;
import com.example.kbagent.knowledge.service.KnowledgeBaseService;
import com.example.kbagent.support.SecuredWebMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SecuredWebMvcTest(KnowledgeBaseController.class)
class KnowledgeBaseControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    KnowledgeBaseService knowledgeBases;

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(get("/api/v1/knowledge-bases"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(knowledgeBases);
    }

    @Test
    void anyLoggedInUserCanCreateAKnowledgeBase() throws Exception {
        when(knowledgeBases.create(eq(USER), any(CreateKnowledgeBase.class))).thenReturn(new KnowledgeBaseView(
                5L, "差旅制度", null, Visibility.PRIVATE, USER.userId(), KbAccess.MANAGE, Instant.now()));

        mvc.perform(post("/api/v1/knowledge-bases").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"差旅制度\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.myAccess").value("MANAGE"));
    }

    @Test
    void createIsValidated() throws Exception {
        mvc.perform(post("/api/v1/knowledge-bases").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data[0].field").value("name"));
        verifyNoInteractions(knowledgeBases);
    }

    @Test
    void inaccessibleKnowledgeBaseIs404AndReadOnlyAccessIs403() throws Exception {
        when(knowledgeBases.get(USER, 7L)).thenThrow(BusinessException.notFound("知识库"));
        doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(knowledgeBases).delete(USER, 8L);

        mvc.perform(get("/api/v1/knowledge-bases/7").with(as(USER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("知识库不存在"));
        mvc.perform(delete("/api/v1/knowledge-bases/8").with(as(USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void putMemberRequiresAValidPermission() throws Exception {
        mvc.perform(put("/api/v1/knowledge-bases/5/members/3").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"permission\":\"OWNER\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/knowledge-bases/5/members/3").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(knowledgeBases);

        mvc.perform(put("/api/v1/knowledge-bases/5/members/3").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"permission\":\"EDITOR\"}"))
                .andExpect(status().isOk());
        verify(knowledgeBases).putMember(USER, 5L, 3L, KbPermission.EDITOR);
    }

    @Test
    void pagingParametersAreValidated() throws Exception {
        mvc.perform(get("/api/v1/knowledge-bases").param("size", "500").with(as(USER)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(knowledgeBases);
    }
}
