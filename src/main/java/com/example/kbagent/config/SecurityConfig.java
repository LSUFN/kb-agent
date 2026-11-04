package com.example.kbagent.config;

import com.example.kbagent.security.AccessTokenBlacklist;
import com.example.kbagent.security.ActiveTokenValidator;
import com.example.kbagent.security.ActorAuthenticationConverter;
import com.example.kbagent.security.RestAccessDeniedHandler;
import com.example.kbagent.security.RestAuthenticationEntryPoint;
import com.example.kbagent.security.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全配置：无状态 JWT（oauth2-resource-server），所有接口默认需要登录。
 * <p>
 * 401/403 由 RestAuthenticationEntryPoint / RestAccessDeniedHandler 输出统一响应体；
 * 资源不属于当前用户时由业务层返回 404（§6.1），不在这里处理。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                               ActorAuthenticationConverter actorAuthenticationConverter,
                                               RestAuthenticationEntryPoint authenticationEntryPoint,
                                               RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                // 纯 Bearer Token API，不使用 Cookie 会话，不存在 CSRF 风险
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 错误转发必须放行，否则匿名请求出错时真实状态码会被 401 掩盖
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/system/ping").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // prometheus 等其余端点只对 ADMIN 开放（§6.5）
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(actorAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /** 校验签名（HS256）、有效期（默认允许 60 秒时钟偏差）、签发者，以及 jti 未被登出。 */
    @Bean
    JwtDecoder jwtDecoder(SecurityProperties properties, AccessTokenBlacklist blacklist) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(properties.jwt().secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()),
                new ActiveTokenValidator(blacklist)));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(SecurityProperties properties) {
        return NimbusJwtEncoder.withSecretKey(properties.jwt().secretKey())
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    /** 默认 BCrypt，哈希带 {bcrypt} 前缀，以后更换算法时旧口令仍可校验。 */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
