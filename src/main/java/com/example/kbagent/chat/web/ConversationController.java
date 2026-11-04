package com.example.kbagent.chat.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import com.example.kbagent.chat.service.ConversationCommands.ConversationView;
import com.example.kbagent.chat.service.ConversationCommands.CreateConversation;
import com.example.kbagent.chat.service.ConversationCommands.MessageView;
import com.example.kbagent.chat.service.ConversationCommands.RenameConversation;
import com.example.kbagent.chat.service.ConversationService;
import com.example.kbagent.common.api.ApiResponse;
import com.example.kbagent.common.api.CursorPage;
import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.user.domain.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 会话管理（§6.3）。会话只属于本人，访问别人的会话返回 404。提问接口见 {@link ChatController}。 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService conversations;

    public ConversationController(ConversationService conversations) {
        this.conversations = conversations;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ConversationView>> create(@AuthenticationPrincipal Actor actor,
                                                                @Valid @RequestBody CreateConversation command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(conversations.create(actor, command)));
    }

    @GetMapping
    public ApiResponse<PageResponse<ConversationView>> list(@AuthenticationPrincipal Actor actor,
                                                            @RequestParam(required = false) @Size(max = 64) String keyword,
                                                            @RequestParam(defaultValue = "1") @Min(1) int page,
                                                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(conversations.list(actor, keyword, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<ConversationView> get(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
        return ApiResponse.ok(conversations.get(actor, id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<ConversationView> rename(@AuthenticationPrincipal Actor actor, @PathVariable long id,
                                                @Valid @RequestBody RenameConversation command) {
        return ApiResponse.ok(conversations.rename(actor, id, command.title()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal Actor actor, @PathVariable long id) {
        conversations.delete(actor, id);
        return ApiResponse.ok();
    }

    /** 游标分页：不传 beforeId 时返回最新的一页；items 按时间正序。 */
    @GetMapping("/{id}/messages")
    public ApiResponse<CursorPage<MessageView>> messages(@AuthenticationPrincipal Actor actor, @PathVariable long id,
                                                         @RequestParam(required = false) Long beforeId,
                                                         @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(conversations.messages(actor, id, beforeId, size));
    }
}
