package com.example.kbagent.chat.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 消息（表 chat_message）。助手消息先以 STREAMING 占位，流式输出期间不写库，结束时一次性写入最终内容（§4.3）。
 * 状态只能通过下面的方法按 {@link MessageStatus} 的规则变化。
 */
@Entity
@Table(name = "chat_message")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "text")
    private String content = "";

    /** JSON 数组：[{index, documentId, filename, page, snippet, score}]。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String citations;

    @Enumerated(EnumType.STRING)
    @Column(name = "citation_mode", length = 16)
    private CitationMode citationMode;

    /** JSON 数组：[{callId, tool, args, decision, ok, latencyMs, summary}]，只有 AGENT 模式会写入。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_steps", columnDefinition = "jsonb")
    private String toolSteps;

    /** JSON 数组：数字核对找不到出处的数字。只有 AGENT 模式会写入（全部有出处时为 []），KB_QA 为 null。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "unverified_numbers", columnDefinition = "jsonb")
    private String unverifiedNumbers;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MessageStatus status;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "trace_id", length = 64, updatable = false)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ChatMessage() {
        // JPA
    }

    public static ChatMessage question(long conversationId, String content, String traceId) {
        ChatMessage message = new ChatMessage();
        message.conversationId = conversationId;
        message.role = MessageRole.USER;
        message.content = content;
        message.status = MessageStatus.OK;
        message.traceId = traceId;
        return message;
    }

    public static ChatMessage answerPlaceholder(long conversationId, String traceId) {
        ChatMessage message = new ChatMessage();
        message.conversationId = conversationId;
        message.role = MessageRole.ASSISTANT;
        message.status = MessageStatus.STREAMING;
        message.traceId = traceId;
        return message;
    }

    /**
     * @param toolStepsJson         没有工具调用时为 null
     * @param unverifiedNumbersJson 没有做数字核对时为 null
     */
    public void complete(String finalContent, CitationMode mode, String citationsJson, String toolStepsJson,
                         String unverifiedNumbersJson) {
        transitionTo(MessageStatus.OK);
        this.content = finalContent;
        this.citationMode = mode;
        this.citations = citationsJson;
        this.toolSteps = toolStepsJson;
        this.unverifiedNumbers = unverifiedNumbersJson;
    }

    /** 保留已经生成的部分内容和已经执行的工具步骤。 */
    public void fail(String partialContent, String code, String toolStepsJson) {
        transitionTo(MessageStatus.FAILED);
        this.content = partialContent;
        this.errorCode = code;
        this.toolSteps = toolStepsJson;
    }

    /** 客户端断开（用户点击“停止生成”）：保留已经生成的部分内容和已经执行的工具步骤。 */
    public void interrupt(String partialContent, String code, String toolStepsJson) {
        transitionTo(MessageStatus.INTERRUPTED);
        this.content = partialContent;
        this.errorCode = code;
        this.toolSteps = toolStepsJson;
    }

    private void transitionTo(MessageStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Message " + id + " cannot move from " + status + " to " + target);
        }
        this.status = target;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long id() {
        return id;
    }

    public long conversationId() {
        return conversationId;
    }

    public MessageRole role() {
        return role;
    }

    public String content() {
        return content;
    }

    public String citations() {
        return citations;
    }

    public CitationMode citationMode() {
        return citationMode;
    }

    public String toolSteps() {
        return toolSteps;
    }

    public String unverifiedNumbers() {
        return unverifiedNumbers;
    }

    /** 回答中有找不到出处的数字（只对做过数字核对的回答成立）。 */
    public boolean hasUnverifiedNumbers() {
        return unverifiedNumbers != null && !unverifiedNumbers.isBlank() && !unverifiedNumbers.strip().equals("[]");
    }

    public MessageStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public String traceId() {
        return traceId;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
