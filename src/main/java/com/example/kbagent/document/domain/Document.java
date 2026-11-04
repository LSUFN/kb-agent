package com.example.kbagent.document.domain;

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
 * 文档（表 document）。状态只能通过下面的方法按 {@link DocumentStatus} 的规则变化。
 */
@Entity
@Table(name = "document")
public class Document {

    private static final int MAX_ERROR_MESSAGE = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_id", nullable = false, updatable = false)
    private Long kbId;

    @Column(nullable = false, length = 255)
    private String filename;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_hash", nullable = false, length = 64, updatable = false)
    private String contentHash;

    @Column(name = "storage_path", nullable = false, length = 512, updatable = false)
    private String storagePath;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DocumentStatus status;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = MAX_ERROR_MESSAGE)
    private String errorMessage;

    @Column(name = "embedding_model", length = 64)
    private String embeddingModel;

    @Column(name = "uploaded_by", nullable = false, updatable = false)
    private Long uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Document() {
        // JPA
    }

    public static Document create(long kbId, String filename, String contentHash, String storagePath,
                                  long sizeBytes, long uploadedBy) {
        Document doc = new Document();
        doc.kbId = kbId;
        doc.filename = filename;
        doc.contentHash = contentHash;
        doc.storagePath = storagePath;
        doc.sizeBytes = sizeBytes;
        doc.uploadedBy = uploadedBy;
        doc.status = DocumentStatus.PENDING;
        return doc;
    }

    public void startProcessing() {
        transitionTo(DocumentStatus.PROCESSING);
    }

    public void markReady(int pageCount, int chunkCount, String embeddingModel) {
        transitionTo(DocumentStatus.READY);
        this.pageCount = pageCount;
        this.chunkCount = chunkCount;
        this.embeddingModel = embeddingModel;
        this.errorCode = null;
        this.errorMessage = null;
    }

    /** @param message 会返回给前端，不得包含上游原始报错等内部信息 */
    public void markFailed(String errorCode, String message) {
        transitionTo(DocumentStatus.FAILED);
        this.errorCode = errorCode;
        this.errorMessage = message == null || message.length() <= MAX_ERROR_MESSAGE
                ? message : message.substring(0, MAX_ERROR_MESSAGE);
    }

    public void markDeleting() {
        transitionTo(DocumentStatus.DELETING);
    }

    /** 重新处理：回到 PENDING，清空上一次的错误。 */
    public void requeue() {
        transitionTo(DocumentStatus.PENDING);
        this.errorCode = null;
        this.errorMessage = null;
    }

    private void transitionTo(DocumentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Document " + id + " cannot move from " + status + " to " + target);
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

    public long kbId() {
        return kbId;
    }

    public String filename() {
        return filename;
    }

    public String contentHash() {
        return contentHash;
    }

    public String storagePath() {
        return storagePath;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public Integer pageCount() {
        return pageCount;
    }

    public int chunkCount() {
        return chunkCount;
    }

    public DocumentStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public String embeddingModel() {
        return embeddingModel;
    }

    public long uploadedBy() {
        return uploadedBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
