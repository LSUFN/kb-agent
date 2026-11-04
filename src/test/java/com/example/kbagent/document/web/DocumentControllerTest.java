package com.example.kbagent.document.web;

import static com.example.kbagent.support.TestActors.USER;
import static com.example.kbagent.support.TestActors.as;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.document.domain.DocumentStatus;
import com.example.kbagent.document.service.DocumentService;
import com.example.kbagent.document.service.DocumentViews.DocumentView;
import com.example.kbagent.document.service.DocumentViews.UploadResult;
import com.example.kbagent.support.SecuredWebMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

@SecuredWebMvcTest(DocumentController.class)
class DocumentControllerTest {

    private static final MockMultipartFile PDF =
            new MockMultipartFile("file", "制度.pdf", "application/pdf", "%PDF-1.7".getBytes());

    @Autowired
    MockMvc mvc;

    @MockitoBean
    DocumentService documents;

    private static DocumentView view(DocumentStatus status) {
        return new DocumentView(9L, 5L, "制度.pdf", status, 8, null, 0, null, null, null, 3L, Instant.now(),
                Instant.now(), null);
    }

    @Test
    void anonymousUploadIs401() throws Exception {
        mvc.perform(multipart("/api/v1/knowledge-bases/5/documents").file(PDF))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(documents);
    }

    @Test
    void newUploadIs202AndDuplicateIs200() throws Exception {
        when(documents.upload(eq(USER), eq(5L), any(MultipartFile.class)))
                .thenReturn(new UploadResult(view(DocumentStatus.PENDING), true))
                .thenReturn(new UploadResult(view(DocumentStatus.READY), false));

        mvc.perform(multipart("/api/v1/knowledge-bases/5/documents").file(PDF).with(as(USER)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        mvc.perform(multipart("/api/v1/knowledge-bases/5/documents").file(PDF).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9));
    }

    @Test
    void missingFilePartIs400() throws Exception {
        mvc.perform(multipart("/api/v1/knowledge-bases/5/documents").with(as(USER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        verifyNoInteractions(documents);
    }

    @Test
    void unknownStatusFilterIs400() throws Exception {
        mvc.perform(get("/api/v1/knowledge-bases/5/documents").param("status", "DONE").with(as(USER)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(documents);
    }

    @Test
    void listPassesStatusFilterAndOneBasedPage() throws Exception {
        when(documents.list(USER, 5L, DocumentStatus.FAILED, 2, 10))
                .thenReturn(new PageResponse<>(java.util.List.of(view(DocumentStatus.FAILED)), 2, 10, 11));

        mvc.perform(get("/api/v1/knowledge-bases/5/documents").param("status", "FAILED")
                        .param("page", "2").param("size", "10").with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(11));
    }

    @Test
    void deleteIsAsynchronous() throws Exception {
        when(documents.delete(USER, 9L)).thenReturn(view(DocumentStatus.DELETING));

        mvc.perform(delete("/api/v1/documents/9").with(as(USER)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("DELETING"));
        verify(documents).delete(USER, 9L);
    }
}
