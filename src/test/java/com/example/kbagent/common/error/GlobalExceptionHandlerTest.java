package com.example.kbagent.common.error;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.example.kbagent.common.api.ApiResponse;
import com.example.kbagent.common.trace.TraceContext;
import com.example.kbagent.common.trace.TraceIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 使用 standalone MockMvc 测试异常到统一响应体的映射，不启动 Spring 容器。
 */
class GlobalExceptionHandlerTest {

    record CreateRequest(@NotBlank String name, @Size(max = 5) String code) {
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/ok")
        ApiResponse<String> ok() {
            return ApiResponse.ok("hello");
        }

        @GetMapping("/probe/not-found")
        ApiResponse<Void> notFound() {
            throw BusinessException.notFound("会话");
        }

        @GetMapping("/probe/forbidden")
        ApiResponse<Void> forbidden() {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        @GetMapping("/probe/boom")
        ApiResponse<Void> boom() {
            throw new IllegalStateException("db password=secret123 leaked in message");
        }

        @PostMapping("/probe/create")
        ApiResponse<String> create(@Valid @RequestBody CreateRequest request) {
            return ApiResponse.ok(request.name());
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void successResponseHasOkCodeAndTraceId() throws Exception {
        mvc.perform(get("/probe/ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").value("hello"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(header().exists(TraceContext.HEADER));
    }

    @Test
    void businessExceptionMapsToItsHttpStatus() throws Exception {
        mvc.perform(get("/probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("会话不存在"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        mvc.perform(get("/probe/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(ErrorCode.FORBIDDEN.defaultMessage()));
    }

    @Test
    void unexpectedExceptionDoesNotLeakInternalMessage() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.defaultMessage()))
                .andExpect(content().string(not(containsString("secret123"))));
    }

    @Test
    void validationErrorsAreReturnedPerField() throws Exception {
        mvc.perform(post("/probe/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"code\":\"toolong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mvc.perform(post("/probe/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void unsupportedMethodIs405() throws Exception {
        mvc.perform(post("/probe/ok"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
