package com.example.kbagent.system.web;

import java.time.Instant;

import com.example.kbagent.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M0 冒烟接口：验证统一响应体与 traceId 链路。M1 接入认证后此接口保持公开。
 */
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    public record PingResult(String status, Instant serverTime) {
    }

    @GetMapping("/ping")
    public ApiResponse<PingResult> ping() {
        return ApiResponse.ok(new PingResult("ok", Instant.now()));
    }
}
