package com.example.kbagent.knowledge.domain;

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

@Entity
@Table(name = "knowledge_base")
public class KnowledgeBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String description;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Visibility visibility;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected KnowledgeBase() {
        // JPA
    }

    public static KnowledgeBase create(String name, String description, long ownerId, Visibility visibility) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.name = name;
        kb.description = description;
        kb.ownerId = ownerId;
        kb.visibility = visibility == null ? Visibility.PRIVATE : visibility;
        return kb;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void describe(String newDescription) {
        this.description = newDescription;
    }

    public void changeVisibility(Visibility newVisibility) {
        this.visibility = newVisibility;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public long ownerId() {
        return ownerId;
    }

    public Visibility visibility() {
        return visibility;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
