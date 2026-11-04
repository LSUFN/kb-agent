package com.example.kbagent.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * S5：MVC 返回 Flux&lt;ServerSentEvent&gt; + Spring Security。不需要 API Key。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S5SseSecuritySpikeTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private HttpRequest request(String path, boolean authenticated) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (authenticated) {
            String token = Base64.getEncoder()
                    .encodeToString("spike:spike-pass".getBytes(StandardCharsets.UTF_8));
            b.header("Authorization", "Basic " + token);
        }
        return b.build();
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        HttpResponse<String> response = http.send(request("/spike/sse/short", false),
                HttpResponse.BodyHandlers.ofString());
        SpikeReport.record("S5", "未认证访问 SSE 的状态码", response.statusCode());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void authenticatedStreamCompletesWithAllEvents() throws Exception {
        HttpResponse<Stream<String>> response = http.send(request("/spike/sse/short", true),
                HttpResponse.BodyHandlers.ofLines());
        List<String> dataLines;
        try (Stream<String> lines = response.body()) {
            dataLines = lines.filter(l -> l.startsWith("data:")).toList();
        }
        SpikeReport.record("S5", "认证后 SSE 状态码 / 收到的 data 行数", response.statusCode() + " / " + dataLines.size());
        SpikeReport.record("S5", "流内线程名", SseSpikeController.STREAM_THREAD.get());
        SpikeReport.record("S5", "流内 SecurityContextHolder 读到的用户（预期可能为 <null>）",
                SseSpikeController.AUTH_IN_STREAM.get());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(dataLines).hasSize(5);
        assertThat(dataLines.getFirst()).contains("user=spike");
    }

    @Test
    void clientDisconnectCancelsUpstreamFlux() throws Exception {
        HttpResponse<InputStream> response = http.send(request("/spike/sse/long", true),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);

        try (InputStream body = response.body()) {
            byte[] buffer = new byte[256];
            int read = body.read(buffer); // 至少读到一部分事件后主动断开
            assertThat(read).isPositive();
        }

        long deadline = System.currentTimeMillis() + 10_000;
        while (!SseSpikeController.CANCELLED.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        SpikeReport.record("S5", "客户端断开后 10 秒内上游 Flux 是否被取消", SseSpikeController.CANCELLED.get());
        assertThat(SseSpikeController.CANCELLED.get()).isTrue();
    }
}
