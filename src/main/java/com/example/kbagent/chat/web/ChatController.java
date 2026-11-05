package com.example.kbagent.chat.web;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import com.example.kbagent.chat.domain.ToolStep;
import com.example.kbagent.chat.service.ChatEvent;
import com.example.kbagent.chat.service.ChatProperties;
import com.example.kbagent.chat.service.ChatService;
import com.example.kbagent.chat.service.ConversationCommands.ChatReply;
import com.example.kbagent.chat.service.ConversationCommands.SendMessage;
import com.example.kbagent.common.api.ApiResponse;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.common.trace.TraceContext;
import com.example.kbagent.user.domain.Actor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 提问（§6.3、§6.4）。限流、会话归属和知识库权限在返回之前同步校验，失败时返回普通的 JSON 错误响应；
 * 开始输出之后的失败通过 error 事件通知。
 */
@RestController
@RequestMapping("/api/v1/conversations")
@EnableConfigurationProperties(ChatProperties.class)
public class ChatController {

    private static final ServerSentEvent<Object> PING = ServerSentEvent.builder().comment("ping").build();
    private static final MediaType EVENT_STREAM_UTF8 = new MediaType(MediaType.TEXT_EVENT_STREAM, StandardCharsets.UTF_8);

    private final ChatService chat;
    private final ChatProperties properties;

    public ChatController(ChatService chat, ChatProperties properties) {
        this.chat = chat;
        this.properties = properties;
    }

    /**
     * 主入口：返回 text/event-stream。客户端断开连接即“停止生成”，已生成的部分会保存，消息标记为 INTERRUPTED。
     * Actor 和 traceId 在请求线程上取出后传入，流内读不到 SecurityContext 和 MDC（S5）。
     */
    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<Object>>> stream(@AuthenticationPrincipal Actor actor,
                                                                @PathVariable long id,
                                                                @Valid @RequestBody SendMessage request) {
        Flux<ServerSentEvent<Object>> events = chat.ask(actor, id, request.content(), TraceContext.currentTraceId())
                .map(ChatController::toSse);
        // 心跳：等待首字期间也有数据写出，代理不会因为空闲断开连接，服务端也能及时发现客户端已断开
        Flux<ServerSentEvent<Object>> heartbeat = Flux.interval(properties.heartbeatInterval())
                .onBackpressureDrop()
                .map(tick -> PING);
        // 显式声明 UTF-8：SSE 规范要求按 UTF-8 解码，但不带 charset 时部分客户端（如 IntelliJ HTTP Client）会按 ISO-8859-1 显示
        return ResponseEntity.ok()
                .contentType(EVENT_STREAM_UTF8)
                .body(events.publish(shared -> Flux.merge(shared, heartbeat.takeUntilOther(shared.then()))));
    }

    /** 非流式版本，供脚本和集成测试使用。模型调用失败时返回 502，消息同样标记为 FAILED。 */
    @PostMapping("/{id}/messages")
    public Mono<ApiResponse<ChatReply>> send(@AuthenticationPrincipal Actor actor, @PathVariable long id,
                                             @Valid @RequestBody SendMessage request) {
        return chat.ask(actor, id, request.content(), TraceContext.currentTraceId())
                .collectList()
                .map(events -> ApiResponse.ok(toReply(events)));
    }

    private static ServerSentEvent<Object> toSse(ChatEvent event) {
        return ServerSentEvent.builder((Object) event).event(event.eventName()).build();
    }

    static ChatReply toReply(List<ChatEvent> events) {
        ChatEvent.Meta meta = null;
        ChatEvent.Retrieval retrieval = null;
        ChatEvent.Citations citations = null;
        ChatEvent.Done done = null;
        StringBuilder content = new StringBuilder();
        Map<String, ChatEvent.ToolStart> started = new LinkedHashMap<>();
        List<ToolStep> toolSteps = new ArrayList<>();
        for (ChatEvent event : events) {
            switch (event) {
                case ChatEvent.Meta m -> meta = m;
                case ChatEvent.Retrieval r -> retrieval = r;
                case ChatEvent.ToolStart t -> started.put(t.callId(), t);
                case ChatEvent.ToolEnd t -> toolSteps.add(new ToolStep(t.callId(), t.name(),
                        started.containsKey(t.callId()) ? started.get(t.callId()).args() : Map.of(), t.decision(),
                        t.ok(), t.latencyMs(), t.summary()));
                case ChatEvent.Delta d -> content.append(d.text());
                case ChatEvent.Citations c -> citations = c;
                case ChatEvent.Done d -> done = d;
                case ChatEvent.Error e -> throw new BusinessException(errorCode(e.code()), e.message());
            }
        }
        if (meta == null || citations == null || done == null) {
            throw new IllegalStateException("incomplete chat events: " + events.size());
        }
        return new ChatReply(meta.messageId(), meta.userMessageId(), meta.traceId(), done.status(),
                content.toString(), citations.mode(), citations.citations(), citations.invalidIndices(),
                retrieval == null ? List.of() : retrieval.candidates(), toolSteps, done.usage(), done.latencyMs(),
                done.firstTokenMs(), done.unverifiedNumbers());
    }

    private static ErrorCode errorCode(String code) {
        try {
            return ErrorCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            return ErrorCode.INTERNAL_ERROR;
        }
    }
}
