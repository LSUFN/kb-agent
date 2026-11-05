package com.example.kbagent.chat.service;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.example.kbagent.chat.domain.Citation;
import com.example.kbagent.chat.domain.CitationMode;
import com.example.kbagent.chat.domain.Conversation;
import com.example.kbagent.chat.domain.ConversationMode;
import com.example.kbagent.chat.domain.MessageRole;
import com.example.kbagent.chat.domain.MessageStatus;
import com.example.kbagent.chat.domain.ToolStep;

/** 会话和消息接口的请求与响应。 */
public final class ConversationCommands {

    private ConversationCommands() {
    }

    /** @param kbId 为 null 时在当前用户有权访问的全部知识库中检索 */
    public record CreateConversation(@NotNull ConversationMode mode, Long kbId, @Size(max = 128) String title) {
    }

    public record RenameConversation(@NotBlank @Size(max = 128) String title) {
    }

    public record SendMessage(@NotBlank @Size(max = 2000) String content) {
    }

    public record ConversationView(long id, String title, ConversationMode mode, Long kbId, Instant createdAt,
                                   Instant updatedAt) {

        public static ConversationView of(Conversation c) {
            return new ConversationView(c.id(), c.title(), c.mode(), c.kbId(), c.createdAt(), c.updatedAt());
        }
    }

    /**
     * @param citations 没有引用时为空列表
     * @param toolSteps AGENT 模式下本轮执行的工具调用，参数已脱敏；没有时为空列表
     */
    public record MessageView(long id, MessageRole role, String content, MessageStatus status,
                              CitationMode citationMode, List<Citation> citations, List<ToolStep> toolSteps,
                              String errorCode, String traceId, Instant createdAt) {
    }

    /**
     * 非流式接口的返回值：把一轮问答的事件汇总成一个结果。
     *
     * @param unverifiedNumbers 数字核对找不到出处的数字；KB_QA 模式不做核对，为 null
     */
    public record ChatReply(long messageId, long userMessageId, String traceId, MessageStatus status, String content,
                            CitationMode citationMode, List<Citation> citations, List<Integer> invalidIndices,
                            List<ChatEvent.RetrievedCandidate> candidates, List<ToolStep> toolSteps,
                            ChatEvent.Usage usage, long latencyMs, Integer firstTokenMs,
                            List<String> unverifiedNumbers) {
    }
}
