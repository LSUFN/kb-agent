package com.example.kbagent.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.example.kbagent.common.error.ErrorCode;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 未认证、Token 无效或已过期、已登出、账号已停用：返回 401 和统一响应体。
 * 不区分 Token 失效的具体原因，避免给攻击者提供信息；账号停用单独提示，便于前端给出明确说明。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorWriter writer;

    public RestAuthenticationEntryPoint(SecurityErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        ErrorCode code = authException instanceof DisabledException ? ErrorCode.ACCOUNT_DISABLED : ErrorCode.UNAUTHORIZED;
        writer.write(response, code);
    }
}
