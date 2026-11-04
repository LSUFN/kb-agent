package com.example.kbagent.spike;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * S5：模拟正式项目的“所有接口都需要认证”配置（正式项目使用 JWT，这里用 Basic 简化）。
 * 关注点：SSE 异步分发是否会被安全过滤链二次拦截。
 * <p>
 * 只在 Servlet Web 环境生效：AiSpikeTest 以 WebEnvironment.NONE 启动，此时没有 HttpSecurity Bean，
 * 不加条件会导致上下文启动失败。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SpikeSecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    UserDetailsService users() {
        return new InMemoryUserDetailsManager(
                User.withUsername("spike").password("{noop}spike-pass").roles("USER").build());
    }
}
