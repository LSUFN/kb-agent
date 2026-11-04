package com.example.kbagent.knowledge.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.knowledge.domain.KbAccess;
import com.example.kbagent.knowledge.domain.KbMember;
import com.example.kbagent.knowledge.domain.KbPermission;
import com.example.kbagent.knowledge.domain.KnowledgeBase;
import com.example.kbagent.knowledge.repository.KbMemberRepository;
import com.example.kbagent.knowledge.repository.KnowledgeBaseRepository;
import com.example.kbagent.knowledge.service.KnowledgeBaseCommands.CreateKnowledgeBase;
import com.example.kbagent.knowledge.service.KnowledgeBaseCommands.KnowledgeBaseView;
import com.example.kbagent.knowledge.service.KnowledgeBaseCommands.MemberView;
import com.example.kbagent.knowledge.service.KnowledgeBaseCommands.UpdateKnowledgeBase;
import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.service.UserAccountService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 知识库与成员管理（§6.2）。所有登录用户都可以创建知识库，并成为它的所有者。 */
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository knowledgeBases;
    private final KbMemberRepository members;
    private final KbPermissionService permissions;
    private final UserAccountService accounts;

    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBases, KbMemberRepository members,
                                KbPermissionService permissions, UserAccountService accounts) {
        this.knowledgeBases = knowledgeBases;
        this.members = members;
        this.permissions = permissions;
        this.accounts = accounts;
    }

    @Transactional
    public KnowledgeBaseView create(Actor actor, CreateKnowledgeBase command) {
        KnowledgeBase kb = knowledgeBases.saveAndFlush(KnowledgeBase.create(command.name().trim(),
                trimToNull(command.description()), actor.userId(), command.visibility()));
        return KnowledgeBaseView.of(kb, KbAccess.MANAGE);
    }

    /** 只返回当前用户有权访问的知识库。@param page 从 1 开始 */
    @Transactional(readOnly = true)
    public PageResponse<KnowledgeBaseView> list(Actor actor, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by("id"));
        Page<KnowledgeBase> result = actor.isAdmin()
                ? knowledgeBases.findAll(pageable)
                : knowledgeBases.findAccessible(actor.userId(), pageable);
        Map<Long, KbPermission> memberships = permissions.membershipsOf(actor);
        Function<KnowledgeBase, KbAccess> access = KbPermissionService.accessResolver(actor, memberships);
        return PageResponse.of(result, kb -> KnowledgeBaseView.of(kb, access.apply(kb)));
    }

    @Transactional(readOnly = true)
    public KnowledgeBaseView get(Actor actor, long kbId) {
        KbPermissionService.Granted granted = permissions.require(actor, kbId, KbAccess.READ);
        return KnowledgeBaseView.of(granted.kb(), granted.access());
    }

    @Transactional
    public KnowledgeBaseView update(Actor actor, long kbId, UpdateKnowledgeBase command) {
        KnowledgeBase kb = permissions.require(actor, kbId, KbAccess.MANAGE).kb();
        if (command.name() != null) {
            kb.rename(command.name().trim());
        }
        if (command.description() != null) {
            kb.describe(trimToNull(command.description()));
        }
        if (command.visibility() != null) {
            kb.changeVisibility(command.visibility());
        }
        return KnowledgeBaseView.of(kb, KbAccess.MANAGE);
    }

    /** 知识库中还有文档（包括正在删除的）时不能删除；成员关系随知识库级联删除。 */
    @Transactional
    public void delete(Actor actor, long kbId) {
        KnowledgeBase kb = permissions.require(actor, kbId, KbAccess.MANAGE).kb();
        if (knowledgeBases.hasDocuments(kbId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "知识库中还有文档，请先删除文档");
        }
        knowledgeBases.delete(kb);
    }

    @Transactional(readOnly = true)
    public List<MemberView> listMembers(Actor actor, long kbId) {
        permissions.require(actor, kbId, KbAccess.MANAGE);
        return members.findMemberRows(kbId).stream()
                .map(row -> new MemberView(row.getUserId(), row.getUsername(), row.getDisplayName(),
                        KbPermission.valueOf(row.getPermission()), row.getCreatedAt()))
                .toList();
    }

    /** 添加成员或修改成员权限。 */
    @Transactional
    public void putMember(Actor actor, long kbId, long userId, KbPermission permission) {
        KnowledgeBase kb = permissions.require(actor, kbId, KbAccess.MANAGE).kb();
        if (kb.ownerId() == userId) {
            throw new BusinessException(ErrorCode.CONFLICT, "所有者已拥有全部权限，不需要添加为成员");
        }
        if (accounts.findActiveActor(userId).isEmpty()) {
            throw BusinessException.notFound("用户");
        }
        members.findById(new KbMember.Key(kbId, userId)).ifPresentOrElse(
                member -> member.changePermission(permission),
                () -> members.save(KbMember.of(kbId, userId, permission)));
    }

    /** 幂等：用户本来就不是成员时也返回成功。 */
    @Transactional
    public void removeMember(Actor actor, long kbId, long userId) {
        permissions.require(actor, kbId, KbAccess.MANAGE);
        members.deleteById(new KbMember.Key(kbId, userId));
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
