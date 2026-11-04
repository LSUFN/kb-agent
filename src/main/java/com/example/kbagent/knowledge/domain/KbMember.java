package com.example.kbagent.knowledge.domain;

import java.io.Serializable;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "kb_member")
public class KbMember {

    @Embeddable
    public record Key(@Column(name = "kb_id") Long kbId, @Column(name = "user_id") Long userId) implements Serializable {
    }

    @EmbeddedId
    private Key id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private KbPermission permission;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected KbMember() {
        // JPA
    }

    public static KbMember of(long kbId, long userId, KbPermission permission) {
        KbMember member = new KbMember();
        member.id = new Key(kbId, userId);
        member.permission = permission;
        return member;
    }

    public void changePermission(KbPermission newPermission) {
        this.permission = newPermission;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public long kbId() {
        return id.kbId();
    }

    public long userId() {
        return id.userId();
    }

    public KbPermission permission() {
        return permission;
    }
}
