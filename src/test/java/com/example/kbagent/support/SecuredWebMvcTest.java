package com.example.kbagent.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.example.kbagent.config.SecurityConfig;
import com.example.kbagent.security.AccessTokenBlacklist;
import com.example.kbagent.security.ActorAuthenticationConverter;
import com.example.kbagent.security.ActorCache;
import com.example.kbagent.security.RestAccessDeniedHandler;
import com.example.kbagent.security.RestAuthenticationEntryPoint;
import com.example.kbagent.security.SecurityErrorWriter;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 接口切片测试（§7）：只启动 Web 层，但使用真实的 SecurityConfig、JWT 校验和 401/403 输出，
 * 只把依赖 Redis 和数据库的 ActorCache、AccessTokenBlacklist 换成 mock。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@WebMvcTest
@ActiveProfiles("test")
@Import({SecurityConfig.class, ActorAuthenticationConverter.class, RestAuthenticationEntryPoint.class,
        RestAccessDeniedHandler.class, SecurityErrorWriter.class})
@MockitoBean(types = {ActorCache.class, AccessTokenBlacklist.class})
public @interface SecuredWebMvcTest {

    @AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
    Class<?>[] value() default {};
}
