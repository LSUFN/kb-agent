package com.example.kbagent.knowledge.service;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.example.kbagent.knowledge.domain.KbAccess;
import com.example.kbagent.knowledge.domain.KbPermission;
import com.example.kbagent.knowledge.domain.KnowledgeBase;
import com.example.kbagent.knowledge.domain.Visibility;

/** 知识库接口的请求与响应。 */
public final class KnowledgeBaseCommands {

    private KnowledgeBaseCommands() {
    }

    /** visibility 不传时为 PRIVATE。 */
    public record CreateKnowledgeBase(
            @NotBlank @Size(max = 128) String name,
            @Size(max = 512) String description,
            Visibility visibility) {
    }

    /** 部分更新：字段为 null 表示不修改。 */
    public record UpdateKnowledgeBase(
            @Size(max = 128) @Pattern(regexp = ".*\\S.*", message = "名称不能为空白") String name,
            @Size(max = 512) String description,
            Visibility visibility) {
    }

    public record PutMember(@NotNull KbPermission permission) {
    }

    /** @param myAccess 当前用户对该知识库的访问级别，前端据此决定显示哪些操作 */
    public record KnowledgeBaseView(long id, String name, String description, Visibility visibility, long ownerId,
                                    KbAccess myAccess, Instant createdAt) {

        public static KnowledgeBaseView of(KnowledgeBase kb, KbAccess access) {
            return new KnowledgeBaseView(kb.id(), kb.name(), kb.description(), kb.visibility(), kb.ownerId(), access,
                    kb.createdAt());
        }
    }

    public record MemberView(long userId, String username, String displayName, KbPermission permission,
                             Instant createdAt) {
    }
}
