package com.example.kbagent.spike;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * S5：Spring MVC 控制器直接返回 Flux&lt;ServerSentEvent&gt;。
 * 记录两件事：客户端断开后上游 Flux 是否被取消；流内（非请求线程）能否读到 SecurityContext。
 */
@RestController
public class SseSpikeController {

    public static final AtomicBoolean CANCELLED = new AtomicBoolean(false);
    public static final AtomicReference<String> AUTH_IN_STREAM = new AtomicReference<>();
    public static final AtomicReference<String> STREAM_THREAD = new AtomicReference<>();

    /** 短流：5 个事件后正常结束，用来验证完整流程与异步分发。 */
    @GetMapping(value = "/spike/sse/short", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> shortStream() {
        String requestUser = currentUser();
        return Flux.interval(Duration.ofMillis(100))
                .take(5)
                .map(i -> {
                    STREAM_THREAD.set(Thread.currentThread().getName());
                    AUTH_IN_STREAM.set(currentUser());
                    return ServerSentEvent.<String>builder("tick-" + i + " user=" + requestUser)
                            .event("delta").id(String.valueOf(i)).build();
                });
    }

    /** 长流：用来验证客户端断开时上游是否被取消。 */
    @GetMapping(value = "/spike/sse/long", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> longStream() {
        CANCELLED.set(false);
        return Flux.interval(Duration.ofMillis(200))
                .take(300)
                .map(i -> ServerSentEvent.<String>builder("tick-" + i).event("delta").build())
                .doOnCancel(() -> CANCELLED.set(true));
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "<null>" : auth.getName();
    }
}
