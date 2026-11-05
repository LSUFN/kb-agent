package com.example.kbagent.chat.service;

import java.util.List;
import java.util.Locale;

import com.example.kbagent.chat.domain.ChatMessage;
import com.example.kbagent.chat.domain.Citation;
import com.example.kbagent.chat.domain.Conversation;
import com.example.kbagent.chat.domain.ToolStep;
import com.example.kbagent.chat.repository.ChatMessageRepository;
import com.example.kbagent.chat.repository.ConversationRepository;
import com.example.kbagent.chat.service.ConversationCommands.ConversationView;
import com.example.kbagent.chat.service.ConversationCommands.CreateConversation;
import com.example.kbagent.chat.service.ConversationCommands.MessageView;
import com.example.kbagent.common.api.CursorPage;
import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.knowledge.domain.KbAccess;
import com.example.kbagent.knowledge.service.KbPermissionService;
import com.example.kbagent.user.domain.Actor;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 会话管理（§6.3）。会话只属于创建者本人，ADMIN 也不能查看别人的会话；
 * 不属于本人的会话一律返回 404。
 */
@Service
public class ConversationService {

    private static final TypeReference<List<Citation>> CITATION_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<ToolStep>> TOOL_STEP_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final KbPermissionService permissions;
    private final ObjectMapper objectMapper;

    public ConversationService(ConversationRepository conversations, ChatMessageRepository messages,
                               KbPermissionService permissions, ObjectMapper objectMapper) {
        this.conversations = conversations;
        this.messages = messages;
        this.permissions = permissions;
        this.objectMapper = objectMapper;
    }

    /** 所有角色都可以创建 AGENT 会话，可用的工具由角色决定（§4.4.3）。 */
    @Transactional
    public ConversationView create(Actor actor, CreateConversation command) {
        if (command.kbId() != null) {
            permissions.require(actor, command.kbId(), KbAccess.READ);
        }
        Conversation saved = conversations.saveAndFlush(
                Conversation.create(actor.userId(), command.mode(), command.kbId(), command.title()));
        return ConversationView.of(saved);
    }

    /** 按更新时间倒序。@param keyword 按标题模糊匹配，忽略大小写；@param page 从 1 开始 */
    @Transactional(readOnly = true)
    public PageResponse<ConversationView> list(Actor actor, String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size,
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        Page<Conversation> result = keyword == null || keyword.isBlank()
                ? conversations.findOwnedPage(actor.userId(), pageable)
                : conversations.findOwnedPageByTitle(actor.userId(), likePattern(keyword), pageable);
        return PageResponse.of(result, ConversationView::of);
    }

    @Transactional(readOnly = true)
    public ConversationView get(Actor actor, long conversationId) {
        return ConversationView.of(owned(actor, conversationId));
    }

    @Transactional
    public ConversationView rename(Actor actor, long conversationId, String title) {
        Conversation conversation = owned(actor, conversationId);
        conversation.rename(title);
        return ConversationView.of(conversations.saveAndFlush(conversation));
    }

    /** 软删除；删除后列表和详情都不再返回，再次删除返回 404。 */
    @Transactional
    public void delete(Actor actor, long conversationId) {
        owned(actor, conversationId).softDelete();
    }

    /**
     * 消息的游标分页。返回的 items 按时间正序（便于直接追加显示），
     * 继续向前加载时把 nextBeforeId 作为 beforeId 传入。
     */
    @Transactional(readOnly = true)
    public CursorPage<MessageView> messages(Actor actor, long conversationId, Long beforeId, int size) {
        owned(actor, conversationId);
        Limit limit = Limit.of(size + 1);
        List<ChatMessage> page = beforeId == null
                ? messages.findByConversationIdOrderByIdDesc(conversationId, limit)
                : messages.findByConversationIdAndIdLessThanOrderByIdDesc(conversationId, beforeId, limit);
        boolean hasMore = page.size() > size;
        List<ChatMessage> items = hasMore ? page.subList(0, size) : page;
        Long nextBeforeId = hasMore ? items.getLast().id() : null;
        return new CursorPage<>(items.reversed().stream().map(this::toView).toList(), nextBeforeId);
    }

    private Conversation owned(Actor actor, long conversationId) {
        return conversations.findOwned(conversationId, actor.userId())
                .orElseThrow(() -> BusinessException.notFound("会话"));
    }

    private MessageView toView(ChatMessage m) {
        List<Citation> citations = m.citations() == null ? List.of()
                : objectMapper.readValue(m.citations(), CITATION_LIST);
        List<ToolStep> toolSteps = m.toolSteps() == null ? List.of()
                : objectMapper.readValue(m.toolSteps(), TOOL_STEP_LIST);
        List<String> unverified = m.unverifiedNumbers() == null ? null
                : objectMapper.readValue(m.unverifiedNumbers(), STRING_LIST);
        return new MessageView(m.id(), m.role(), m.content(), m.status(), m.citationMode(), citations, toolSteps,
                unverified, m.errorCode(), m.traceId(), m.createdAt());
    }

    /** 转义 LIKE 的通配符，避免用户输入的 % 和 _ 被当作通配符。 */
    static String likePattern(String keyword) {
        String escaped = keyword.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
