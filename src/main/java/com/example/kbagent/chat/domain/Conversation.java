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

/** 会话（表 conversation）。删除是软删除。 */
@Entity
@Table(name = "conversation")
public class Conversation {

    public static final String DEFAULT_TITLE = "新会话";
    private static final int AUTO_TITLE_LENGTH = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, length = 128)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private ConversationMode mode;

    /** 为 null 时在用户有权访问的全部知识库中检索；知识库被删除后也会变为 null（ON DELETE SET NULL）。 */
    @Column(name = "kb_id")
    private Long kbId;

    @Column(nullable = false)
    private boolean deleted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Conversation() {
        // JPA
    }

    public static Conversation create(long userId, ConversationMode mode, Long kbId, String title) {
        Conversation conversation = new Conversation();
        conversation.userId = userId;
        conversation.mode = mode;
        conversation.kbId = kbId;
        conversation.title = title == null || title.isBlank() ? DEFAULT_TITLE : title.strip();
        return conversation;
    }

    public void rename(String newTitle) {
        this.title = newTitle.strip();
    }

    public void softDelete() {
        this.deleted = true;
    }

    /** 有新问题：会话排到列表最前面；还是默认标题时，用问题的前 30 个字作为标题。 */
    public void onNewQuestion(String question) {
        if (DEFAULT_TITLE.equals(title)) {
            String normalized = question.strip().replaceAll("\\s+", " ");
            title = normalized.codePointCount(0, normalized.length()) <= AUTO_TITLE_LENGTH ? normalized
                    : normalized.substring(0, normalized.offsetByCodePoints(0, AUTO_TITLE_LENGTH));
        }
        updatedAt = Instant.now();
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

    public long userId() {
        return userId;
    }

    public String title() {
        return title;
    }

    public ConversationMode mode() {
        return mode;
    }

    public Long kbId() {
        return kbId;
    }

    public boolean deleted() {
        return deleted;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
