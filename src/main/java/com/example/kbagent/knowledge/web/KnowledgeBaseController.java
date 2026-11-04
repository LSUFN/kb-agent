package com.example.kbagent.knowledge.web;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.example.kbagent.common.api.ApiResponse;
import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.knowledge.service.KnowledgeBaseCommands.CreateKnowledgeBase;
import com.example.kbagent.knowledge.service.KnowledgeBaseCommands.KnowledgeBaseView;
import com.example.kbagent.knowledge.service.KnowledgeBaseCommands.MemberView;
import com.example.kbagent.knowledge.service.KnowledgeBaseCommands.PutMember;
import com.example.kbagent.knowledge.service.KnowledgeBaseCommands.UpdateKnowledgeBase;
import com.example.kbagent.knowledge.service.KnowledgeBaseService;
import com.example.kbagent.user.domain.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库与成员（§6.2）。权限判断都在 Service 中通过 KbPermissionService 完成：
 * 无权访问返回 404，有权查看但不能修改返回 403。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBases;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBases) {
        this.knowledgeBases = knowledgeBases;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<KnowledgeBaseView>> create(@AuthenticationPrincipal Actor actor,
                                                                 @Valid @RequestBody CreateKnowledgeBase command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(knowledgeBases.create(actor, command)));
    }

    @GetMapping
    public ApiResponse<PageResponse<KnowledgeBaseView>> list(@AuthenticationPrincipal Actor actor,
                                                             @RequestParam(defaultValue = "1") @Min(1) int page,
                                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(knowledgeBases.list(actor, page, size));
    }

    @GetMapping("/{kbId}")
    public ApiResponse<KnowledgeBaseView> get(@AuthenticationPrincipal Actor actor, @PathVariable long kbId) {
        return ApiResponse.ok(knowledgeBases.get(actor, kbId));
    }

    @PatchMapping("/{kbId}")
    public ApiResponse<KnowledgeBaseView> update(@AuthenticationPrincipal Actor actor, @PathVariable long kbId,
                                                 @Valid @RequestBody UpdateKnowledgeBase command) {
        return ApiResponse.ok(knowledgeBases.update(actor, kbId, command));
    }

    @DeleteMapping("/{kbId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal Actor actor, @PathVariable long kbId) {
        knowledgeBases.delete(actor, kbId);
        return ApiResponse.ok();
    }

    @GetMapping("/{kbId}/members")
    public ApiResponse<List<MemberView>> members(@AuthenticationPrincipal Actor actor, @PathVariable long kbId) {
        return ApiResponse.ok(knowledgeBases.listMembers(actor, kbId));
    }

    @PutMapping("/{kbId}/members/{userId}")
    public ApiResponse<Void> putMember(@AuthenticationPrincipal Actor actor, @PathVariable long kbId,
                                       @PathVariable long userId, @Valid @RequestBody PutMember command) {
        knowledgeBases.putMember(actor, kbId, userId, command.permission());
        return ApiResponse.ok();
    }

    @DeleteMapping("/{kbId}/members/{userId}")
    public ApiResponse<Void> removeMember(@AuthenticationPrincipal Actor actor, @PathVariable long kbId,
                                          @PathVariable long userId) {
        knowledgeBases.removeMember(actor, kbId, userId);
        return ApiResponse.ok();
    }
}
