package com.example.kbagent.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import com.example.kbagent.common.api.ApiResponse;
import com.example.kbagent.common.error.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 在安全过滤链中（尚未进入 DispatcherServlet，全局异常处理接管不到）输出统一响应体。
 * TraceIdFilter 排在安全过滤链之前，所以这里的响应同样带 traceId。
 */
@Component
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;

    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, null, null));
    }
}
