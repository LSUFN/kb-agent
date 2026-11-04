package com.example.kbagent.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.example.kbagent.support.TestcontainersConfiguration;
import com.example.kbagent.user.domain.Region;
import com.example.kbagent.user.domain.Role;
import com.example.kbagent.user.domain.SysUser;
import com.example.kbagent.user.repository.SysUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * M1 验收（§8）：三种角色可以登录；401/403/404 语义符合约定；登出后旧 Token 失效；账号停用后立即失效。
 * 使用真实的 PostgreSQL 和 Redis（Testcontainers），走完整的 HTTP 链路。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AuthFlowIT {

    private static final String PASSWORD = "Passw0rd!";

    @Value("${local.server.port}")
    int port;

    @Autowired
    SysUserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    ObjectMapper json;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    // ------------------------------------------------------------------ helpers

    private record Response(int status, JsonNode body, HttpHeaders headers) {

        String code() {
            return body.path("code").asString();
        }

        JsonNode data() {
            return body.path("data");
        }
    }

    private record Session(String accessToken, String refreshToken, long userId) {
    }

    private Response call(String method, String path, String token, String jsonBody) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, jsonBody == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(jsonBody));
        if (jsonBody != null) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        boolean isJson = response.headers().firstValue("Content-Type").orElse("").contains("json");
        JsonNode body = isJson ? json.readTree(response.body()) : json.missingNode();
        return new Response(response.statusCode(), body, response.headers());
    }

    private String createUser(Role role, Set<Region> regions, boolean enabled) {
        String username = role.name().toLowerCase(Locale.ROOT) + "_" + UUID.randomUUID().toString().substring(0, 8);
        SysUser user = SysUser.create(username, passwordEncoder.encode(PASSWORD), "IT " + username, role, regions);
        user.setEnabled(enabled);
        users.save(user);
        return username;
    }

    private String createUser(Role role) {
        return createUser(role, role == Role.ANALYST ? Set.of(Region.EAST) : Set.of(), true);
    }

    private Response loginRequest(String username, String password) throws Exception {
        return call("POST", "/api/v1/auth/login", null,
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    private Session login(String username) throws Exception {
        Response response = loginRequest(username, PASSWORD);
        assertThat(response.status()).as(response.body().toString()).isEqualTo(200);
        return new Session(response.data().path("accessToken").asString(),
                response.data().path("refreshToken").asString(),
                response.data().path("user").path("id").asLong());
    }

    private Response refresh(String refreshToken) throws Exception {
        return call("POST", "/api/v1/auth/refresh", null, "{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    private Response me(String accessToken) throws Exception {
        return call("GET", "/api/v1/users/me", accessToken, null);
    }

    // ------------------------------------------------------------------ 登录

    @Test
    void everyRoleCanLogInAndProfileComesFromServer() throws Exception {
        for (Role role : Role.values()) {
            String username = createUser(role);
            Response login = loginRequest(username, PASSWORD);

            assertThat(login.status()).isEqualTo(200);
            assertThat(login.data().path("tokenType").asString()).isEqualTo("Bearer");
            assertThat(login.data().path("expiresIn").asLong()).isEqualTo(900);
            assertThat(login.data().path("refreshExpiresIn").asLong()).isEqualTo(604800);

            Response me = me(login.data().path("accessToken").asString());
            assertThat(me.status()).isEqualTo(200);
            assertThat(me.data().path("username").asString()).isEqualTo(username);
            assertThat(me.data().path("role").asString()).isEqualTo(role.name());
        }
    }

    @Test
    void analystSeesRegionScopeInProfile() throws Exception {
        String analyst = createUser(Role.ANALYST, Set.of(Region.NORTH, Region.EAST), true);

        Response me = me(login(analyst).accessToken());

        assertThat(me.data().path("regionScope").toString()).isEqualTo("[\"EAST\",\"NORTH\"]");
    }

    @Test
    void wrongPasswordAndUnknownUserAreIndistinguishable() throws Exception {
        String username = createUser(Role.USER);

        Response wrongPassword = loginRequest(username, "wrong-password");
        Response unknownUser = loginRequest("nobody_" + UUID.randomUUID().toString().substring(0, 8), PASSWORD);

        assertThat(wrongPassword.status()).isEqualTo(401);
        assertThat(wrongPassword.code()).isEqualTo("BAD_CREDENTIALS");
        assertThat(unknownUser.status()).isEqualTo(401);
        assertThat(unknownUser.body().path("message")).isEqualTo(wrongPassword.body().path("message"));
    }

    @Test
    void usernameIsCaseInsensitive() throws Exception {
        String username = createUser(Role.USER);

        assertThat(loginRequest(username.toUpperCase(Locale.ROOT), PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void disabledAccountCannotLogIn() throws Exception {
        String username = createUser(Role.USER, Set.of(), false);

        Response response = loginRequest(username, PASSWORD);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("ACCOUNT_DISABLED");
    }

    // ------------------------------------------------------------------ 刷新与登出

    @Test
    void refreshRotatesTokensAndReuseRevokesTheWholeFamily() throws Exception {
        Session session = login(createUser(Role.USER));

        Response rotated = refresh(session.refreshToken());
        assertThat(rotated.status()).isEqualTo(200);
        String newRefreshToken = rotated.data().path("refreshToken").asString();
        assertThat(newRefreshToken).isNotEqualTo(session.refreshToken());
        assertThat(me(rotated.data().path("accessToken").asString()).status()).isEqualTo(200);

        // 旧令牌已经轮换掉，再次出现视为被盗用：拒绝，并撤销整个令牌族
        Response reused = refresh(session.refreshToken());
        assertThat(reused.status()).isEqualTo(401);
        assertThat(reused.code()).isEqualTo("REFRESH_TOKEN_INVALID");
        assertThat(refresh(newRefreshToken).code()).isEqualTo("REFRESH_TOKEN_INVALID");
    }

    @Test
    void logoutInvalidatesTheOldAccessTokenAndRefreshToken() throws Exception {
        Session session = login(createUser(Role.ANALYST));
        assertThat(me(session.accessToken()).status()).isEqualTo(200);

        Response logout = call("POST", "/api/v1/auth/logout", session.accessToken(),
                "{\"refreshToken\":\"" + session.refreshToken() + "\"}");
        assertThat(logout.status()).isEqualTo(200);

        Response afterLogout = me(session.accessToken());
        assertThat(afterLogout.status()).isEqualTo(401);
        assertThat(afterLogout.code()).isEqualTo("UNAUTHORIZED");
        assertThat(refresh(session.refreshToken()).code()).isEqualTo("REFRESH_TOKEN_INVALID");
    }

    // ------------------------------------------------------------------ 账号状态与角色立即生效

    @Test
    void disablingAnAccountTakesEffectImmediately() throws Exception {
        Session admin = login(createUser(Role.ADMIN));
        Session victim = login(createUser(Role.USER));
        // 先访问一次，让该用户的状态进入缓存；停用后不等缓存过期也必须立即失效
        assertThat(me(victim.accessToken()).status()).isEqualTo(200);

        Response disabled = call("PATCH", "/api/v1/admin/users/" + victim.userId(), admin.accessToken(),
                "{\"enabled\":false}");
        assertThat(disabled.status()).isEqualTo(200);
        assertThat(disabled.data().path("enabled").asBoolean()).isFalse();

        Response afterDisable = me(victim.accessToken());
        assertThat(afterDisable.status()).isEqualTo(401);
        assertThat(afterDisable.code()).isEqualTo("ACCOUNT_DISABLED");
        assertThat(refresh(victim.refreshToken()).code()).isEqualTo("ACCOUNT_DISABLED");
    }

    @Test
    void roleChangesApplyToExistingTokensImmediately() throws Exception {
        Session admin = login(createUser(Role.ADMIN));
        Session user = login(createUser(Role.USER));
        assertThat(call("GET", "/api/v1/admin/users", user.accessToken(), null).status()).isEqualTo(403);

        Response promoted = call("PATCH", "/api/v1/admin/users/" + user.userId(), admin.accessToken(),
                "{\"role\":\"ADMIN\"}");
        assertThat(promoted.status()).isEqualTo(200);

        // 同一个 Token：角色来自数据库而不是 Token，修改后立即生效
        assertThat(call("GET", "/api/v1/admin/users", user.accessToken(), null).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ 401 / 403 / 404

    @Test
    void adminApiAndActuatorAreRestricted() throws Exception {
        Session analyst = login(createUser(Role.ANALYST));
        Session admin = login(createUser(Role.ADMIN));

        Response forbidden = call("GET", "/api/v1/admin/users", analyst.accessToken(), null);
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.code()).isEqualTo("FORBIDDEN");
        assertThat(forbidden.body().path("traceId").asString())
                .isEqualTo(forbidden.headers().firstValue("X-Trace-Id").orElseThrow());

        assertThat(call("GET", "/actuator/health", null, null).status()).isEqualTo(200);
        assertThat(call("GET", "/actuator/prometheus", null, null).status()).isEqualTo(401);
        assertThat(call("GET", "/actuator/prometheus", analyst.accessToken(), null).status()).isEqualTo(403);
        assertThat(call("GET", "/actuator/prometheus", admin.accessToken(), null).status()).isEqualTo(200);
    }

    @Test
    void unknownPathIs401WhenAnonymousAnd404WhenLoggedIn() throws Exception {
        Session session = login(createUser(Role.USER));

        assertThat(call("GET", "/api/v1/does-not-exist", null, null).status()).isEqualTo(401);
        Response notFound = call("GET", "/api/v1/does-not-exist", session.accessToken(), null);
        assertThat(notFound.status()).isEqualTo(404);
        assertThat(notFound.code()).isEqualTo("NOT_FOUND");
    }

    // ------------------------------------------------------------------ 用户管理

    @Test
    void adminManagesUsersThroughTheApi() throws Exception {
        Session admin = login(createUser(Role.ADMIN));
        String username = "new_" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"username":"%s","password":"%s","displayName":"新用户","role":"ANALYST","regionScope":["WEST"]}"""
                .formatted(username.toUpperCase(Locale.ROOT), PASSWORD);

        Response created = call("POST", "/api/v1/admin/users", admin.accessToken(), body);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.data().path("username").asString()).isEqualTo(username);
        assertThat(created.data().path("regionScope").toString()).isEqualTo("[\"WEST\"]");
        assertThat(created.data().has("passwordHash")).isFalse();

        Response duplicate = call("POST", "/api/v1/admin/users", admin.accessToken(), body);
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("USERNAME_TAKEN");

        Response noRegion = call("POST", "/api/v1/admin/users", admin.accessToken(), """
                {"username":"x_%s","password":"%s","displayName":"x","role":"ANALYST","regionScope":[]}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8), PASSWORD));
        assertThat(noRegion.status()).isEqualTo(400);
        assertThat(noRegion.data().path(0).path("field").asString()).isEqualTo("regionScope");

        Response search = call("GET", "/api/v1/admin/users?keyword=" + username + "&page=1&size=5",
                admin.accessToken(), null);
        assertThat(search.data().path("total").asLong()).isEqualTo(1);
        assertThat(search.data().path("items").path(0).path("username").asString()).isEqualTo(username);

        assertThat(call("GET", "/api/v1/admin/users/999999999", admin.accessToken(), null).status()).isEqualTo(404);
        assertThat(login(username).accessToken()).isNotBlank();
    }

    @Test
    void adminCannotLockThemselvesOut() throws Exception {
        Session admin = login(createUser(Role.ADMIN));
        String self = "/api/v1/admin/users/" + admin.userId();

        assertThat(call("PATCH", self, admin.accessToken(), "{\"enabled\":false}").status()).isEqualTo(409);
        assertThat(call("PATCH", self, admin.accessToken(), "{\"role\":\"USER\"}").status()).isEqualTo(409);
        assertThat(me(admin.accessToken()).data().path("role").asString()).isEqualTo("ADMIN");
    }
}
