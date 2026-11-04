package com.example.kbagent.knowledge.service;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.knowledge.domain.KbAccess;
import com.example.kbagent.knowledge.domain.KbMember;
import com.example.kbagent.knowledge.domain.KbPermission;
import com.example.kbagent.knowledge.domain.KnowledgeBase;
import com.example.kbagent.knowledge.domain.Visibility;
import com.example.kbagent.knowledge.repository.KbMemberRepository;
import com.example.kbagent.knowledge.repository.KnowledgeBaseRepository;
import com.example.kbagent.user.domain.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库访问控制的唯一入口（§3.3：接口鉴权和检索过滤共用同一套规则）。
 * <p>
 * 访问级别：ADMIN 和所有者 MANAGE；EDITOR 成员 EDIT；READER 成员和公开知识库 READ；其余 NONE。
 * 没有任何访问权限时返回 404，不暴露知识库是否存在（§6.1）；能看到但权限不够时返回 403。
 */
@Service
public class KbPermissionService {

    private final KnowledgeBaseRepository knowledgeBases;
    private final KbMemberRepository members;

    public KbPermissionService(KnowledgeBaseRepository knowledgeBases, KbMemberRepository members) {
        this.knowledgeBases = knowledgeBases;
        this.members = members;
    }

    /** 鉴权通过的知识库，以及当前用户的实际访问级别（可能高于要求的级别）。 */
    public record Granted(KnowledgeBase kb, KbAccess access) {
    }

    @Transactional(readOnly = true)
    public Granted require(Actor actor, long kbId, KbAccess required) {
        KnowledgeBase kb = knowledgeBases.findById(kbId).orElseThrow(() -> BusinessException.notFound("知识库"));
        KbAccess access = accessOf(actor, kb, members.findById(new KbMember.Key(kbId, actor.userId()))
                .map(KbMember::permission).orElse(null));
        if (access == KbAccess.NONE) {
            throw BusinessException.notFound("知识库");
        }
        if (!access.includes(required)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return new Granted(kb, access);
    }

    /** 当前用户在各个知识库中的成员权限，key 为知识库 ID。 */
    @Transactional(readOnly = true)
    public Map<Long, KbPermission> membershipsOf(Actor actor) {
        return members.findByUserId(actor.userId()).stream()
                .collect(Collectors.toMap(KbMember::kbId, KbMember::permission, (a, b) -> a));
    }

    /** @param membership 用户在该知识库中的成员权限，不是成员时为 null */
    public static KbAccess accessOf(Actor actor, KnowledgeBase kb, KbPermission membership) {
        if (actor.isAdmin() || kb.ownerId() == actor.userId()) {
            return KbAccess.MANAGE;
        }
        if (membership == KbPermission.EDITOR) {
            return KbAccess.EDIT;
        }
        if (membership == KbPermission.READER || kb.visibility() == Visibility.PUBLIC) {
            return KbAccess.READ;
        }
        return KbAccess.NONE;
    }

    static Function<KnowledgeBase, KbAccess> accessResolver(Actor actor, Map<Long, KbPermission> memberships) {
        return kb -> accessOf(actor, kb, memberships.get(kb.id()));
    }
}
