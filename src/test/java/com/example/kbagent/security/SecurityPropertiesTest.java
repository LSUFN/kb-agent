package com.example.kbagent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class SecurityPropertiesTest {

    private static SecurityProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("app.security", SecurityProperties.class);
    }

    @Test
    void defaultsMatchTheDesign() {
        SecurityProperties props = bind(Map.of("app.security.jwt.secret", "s".repeat(32)));

        assertThat(props.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(props.refreshTokenTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(props.actorCacheTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.loginRateLimitPerMinute()).isEqualTo(10);
        assertThat(props.jwt().issuer()).isEqualTo("kb-agent");
    }

    @Test
    void missingOrShortSecretPreventsStartup() {
        assertThatThrownBy(() -> bind(Map.of())).isInstanceOf(BindException.class)
                .rootCause().hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> bind(Map.of("app.security.jwt.secret", "too-short")))
                .isInstanceOf(BindException.class);
    }

    @Test
    void secretLengthIsMeasuredInBytes() {
        // 11 个汉字 = 33 个 UTF-8 字节
        assertThat(new SecurityProperties.Jwt("密".repeat(11), "kb-agent").secretKey().getEncoded()).hasSize(33);
    }

    @Test
    void toStringNeverPrintsTheSecret() {
        String secret = "super-secret-value-that-must-not-leak";

        assertThat(new SecurityProperties.Jwt(secret, "kb-agent").toString()).doesNotContain(secret);
    }
}
