package com.example.kbagent.report.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 报告（表 report）。生成后不再修改。 */
@Entity
@Table(name = "report")
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "conversation_id", updatable = false)
    private Long conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 32, updatable = false)
    private ReportType reportType;

    @Column(nullable = false, length = 200, updatable = false)
    private String title;

    /** 收敛之后实际生效的参数（含区域），JSON。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private String params;

    @Column(name = "content_md", nullable = false, columnDefinition = "text", updatable = false)
    private String contentMd;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Report() {
        // JPA
    }

    public static Report create(long userId, Long conversationId, ReportType type, String title, String paramsJson,
                                String contentMd) {
        Report report = new Report();
        report.userId = userId;
        report.conversationId = conversationId;
        report.reportType = type;
        report.title = title;
        report.params = paramsJson;
        report.contentMd = contentMd;
        return report;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long id() {
        return id;
    }

    public long userId() {
        return userId;
    }

    public Long conversationId() {
        return conversationId;
    }

    public ReportType reportType() {
        return reportType;
    }

    public String title() {
        return title;
    }

    public String params() {
        return params;
    }

    public String contentMd() {
        return contentMd;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
