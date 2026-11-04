package com.example.kbagent.retrieval.web;

import static com.example.kbagent.support.TestActors.USER;
import static com.example.kbagent.support.TestActors.as;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.retrieval.service.RetrievalService;
import com.example.kbagent.retrieval.service.RetrievalService.Candidate;
import com.example.kbagent.support.SecuredWebMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SecuredWebMvcTest(RetrievalController.class)
class RetrievalControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RetrievalService retrieval;

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(post("/api/v1/knowledge-bases/9/search")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"报销\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(retrieval);
    }

    @Test
    void requestIsValidated() throws Exception {
        mvc.perform(post("/api/v1/knowledge-bases/9/search").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/knowledge-bases/9/search").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"报销\",\"topK\":21}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(retrieval);
    }

    @Test
    void hitsAreFlaggedAgainstTheThresholdAndDefaultTopKIsUsed() throws Exception {
        when(retrieval.defaultTopK()).thenReturn(5);
        when(retrieval.minScore()).thenReturn(0.5);
        when(retrieval.search(eq(USER), eq(9L), eq("报销期限"), eq(5), any())).thenReturn(List.of(
                new Candidate(1, 11L, 101L, "差旅制度.pdf", 3, 0, "报销须在出差结束后 30 天内提交。", 0.82),
                new Candidate(2, 12L, 101L, "差旅制度.pdf", 4, 1, "住宿标准……", 0.31)));

        mvc.perform(post("/api/v1/knowledge-bases/9/search").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"报销期限\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minScore").value(0.5))
                .andExpect(jsonPath("$.data.hits[0].pageNumber").value(3))
                .andExpect(jsonPath("$.data.hits[0].aboveThreshold").value(true))
                .andExpect(jsonPath("$.data.hits[1].aboveThreshold").value(false));
    }

    @Test
    void inaccessibleKnowledgeBaseIs404() throws Exception {
        when(retrieval.search(eq(USER), eq(8L), any(), any(Integer.class), any()))
                .thenThrow(BusinessException.notFound("知识库"));

        mvc.perform(post("/api/v1/knowledge-bases/8/search").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"报销\",\"topK\":3}"))
                .andExpect(status().isNotFound());
    }
}
