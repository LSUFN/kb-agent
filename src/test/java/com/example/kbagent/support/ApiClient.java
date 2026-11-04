package com.example.kbagent.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import com.example.kbagent.user.domain.Region;
import com.example.kbagent.user.domain.Role;
import com.example.kbagent.user.domain.SysUser;
import com.example.kbagent.user.repository.SysUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 集成测试使用的 HTTP 客户端：统一响应体解析、登录、multipart 上传、SSE、轮询。 */
public final class ApiClient {

    public static final String PASSWORD = "Passw0rd!";

    public record Response(int status, JsonNode body) {

        public String code() {
            return body.path("code").asString();
        }

        public JsonNode data() {
            return body.path("data");
        }
    }

    /** 登录后的用户。 */
    public record User(long id, String username, String token) {
    }

    /** 一个 SSE 事件，data 按 JSON 解析。 */
    public record SseEvent(String name, JsonNode data) {
    }

    /**
     * 一次 SSE 请求的结果。
     *
     * @param comments  收到的注释行（心跳 {@code :ping}）数量
     * @param errorBody 状态码不是 200 时的 JSON 响应体
     */
    public record SseStream(int status, List<SseEvent> events, int comments, JsonNode errorBody) {

        public List<String> names() {
            return events.stream().map(SseEvent::name).toList();
        }

        public SseEvent first(String name) {
            return events.stream().filter(e -> e.name().equals(name)).findFirst()
                    .orElseThrow(() -> new AssertionError("no '" + name + "' event in " + names()));
        }

        /** 所有 delta 拼起来的文本。 */
        public String text() {
            StringBuilder text = new StringBuilder();
            events.stream().filter(e -> e.name().equals("delta"))
                    .forEach(e -> text.append(e.data().path("text").asString()));
            return text.toString();
        }
    }

    private final int port;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    public ApiClient(int port, ObjectMapper json) {
        this.port = port;
        this.json = json;
    }

    public Response get(String path, User user) {
        return send(request(path, user).GET());
    }

    public Response post(String path, User user, String jsonBody) {
        return send(request(path, user).header("Content-Type", "application/json")
                .POST(jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody)));
    }

    public Response put(String path, User user, String jsonBody) {
        return send(request(path, user).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(jsonBody)));
    }

    public Response patch(String path, User user, String jsonBody) {
        return send(request(path, user).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody)));
    }

    public Response delete(String path, User user) {
        return send(request(path, user).DELETE());
    }

    public Response upload(String path, User user, String filename, byte[] content) {
        String boundary = "----kbagent" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(request(path, user).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())));
    }

    /** POST 到 SSE 接口并读到流结束。 */
    public SseStream stream(String path, User user, String jsonBody) {
        return stream(path, user, jsonBody, Integer.MAX_VALUE);
    }

    /**
     * POST 到 SSE 接口；收到 deltasBeforeDisconnect 个 delta 事件后主动关闭连接（模拟用户点击“停止生成”），
     * 返回关闭前收到的事件。
     */
    public SseStream stream(String path, User user, String jsonBody, int deltasBeforeDisconnect) {
        HttpRequest request = request(path, user)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                try (InputStream body = response.body()) {
                    String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                    return new SseStream(response.statusCode(), List.of(), 0,
                            text.isBlank() ? json.missingNode() : json.readTree(text));
                }
            }
            List<SseEvent> events = new ArrayList<>();
            int comments = 0;
            int deltas = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String name = null;
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (!data.isEmpty()) {
                            events.add(new SseEvent(name == null ? "message" : name, json.readTree(data.toString())));
                            if ("delta".equals(name) && ++deltas >= deltasBeforeDisconnect) {
                                break;
                            }
                        }
                        name = null;
                        data.setLength(0);
                    } else if (line.startsWith(":")) {
                        comments++;
                    } else if (line.startsWith("event:")) {
                        name = line.substring("event:".length()).strip();
                    } else if (line.startsWith("data:")) {
                        data.append(line.substring("data:".length()));
                    }
                }
            }
            return new SseStream(200, List.copyOf(events), comments, json.missingNode());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 在数据库中创建用户并登录。 */
    public User createUserAndLogin(SysUserRepository users, PasswordEncoder encoder, Role role) {
        String username = role.name().toLowerCase(Locale.ROOT) + "_" + UUID.randomUUID().toString().substring(0, 8);
        Set<Region> regions = role == Role.ANALYST ? Set.of(Region.EAST) : Set.of();
        SysUser saved = users.save(SysUser.create(username, encoder.encode(PASSWORD), "IT " + username, role, regions));
        Response login = post("/api/v1/auth/login", null,
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
        assertThat(login.status()).as(login.body().toString()).isEqualTo(200);
        return new User(saved.id(), username, login.data().path("accessToken").asString());
    }

    /** 创建知识库并返回 ID。 */
    public long createKnowledgeBase(User owner, String visibility) {
        Response response = post("/api/v1/knowledge-bases", owner,
                "{\"name\":\"IT 知识库\",\"visibility\":\"" + visibility + "\"}");
        assertThat(response.status()).as(response.body().toString()).isEqualTo(201);
        return response.data().path("id").asLong();
    }

    /** 轮询文档详情，直到满足条件或超时。 */
    public JsonNode awaitDocument(long documentId, User user, Predicate<Response> condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Response last;
        do {
            last = get("/api/v1/documents/" + documentId, user);
            if (condition.test(last)) {
                return last.data();
            }
            sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Document " + documentId + " did not reach the expected state in " + timeout
                + "; last response: " + last.status() + " " + last.body());
    }

    public JsonNode awaitStatus(long documentId, User user, String status) {
        return awaitDocument(documentId, user,
                r -> r.status() == 200 && status.equals(r.data().path("status").asString()), Duration.ofSeconds(20));
    }

    private HttpRequest.Builder request(String path, User user) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30));
        if (user != null) {
            builder.header("Authorization", "Bearer " + user.token());
        }
        return builder;
    }

    private Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            boolean isJson = response.headers().firstValue("Content-Type").orElse("").contains("json");
            return new Response(response.statusCode(),
                    isJson ? json.readTree(response.body()) : json.missingNode());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
