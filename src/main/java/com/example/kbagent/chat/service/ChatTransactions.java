package com.example.kbagent.chat.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.example.kbagent.chat.domain.ChatMessage;
import com.example.kbagent.chat.domain.Citation;
import com.example.kbagent.chat.domain.CitationMode;
import com.example.kbagent.chat.domain.Conversation;
import com.example.kbagent.chat.domain.ConversationMode;
import com.example.kbagent.chat.domain.MessageRole;
import com.example.kbagent.chat.domain.MessageStatus;
import com.example.kbagent.chat.domain.ToolStep;
import com.example.kbagent.chat.repository.ChatMessageRepository;
import com.example.kbagent.chat.repository.ConversationRepository;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.knowledge.domain.KbAccess;
import com.example.kbagent.knowledge.service.KbPermissionService;
import com.example.kbagent.user.domain.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 一轮问答的两个短事务（§4.3）：开始时保存问题和 STREAMING 占位，结束时一次性写入最终状态。
 * 中间的检索、模型调用和工具调用在 {@link ChatRound} 中进行，不持有数据库连接。
 */
@Component
public class ChatTransactions {

    private static final Logger log = LoggerFactory.getLogger(ChatTransactions.class);

    /** 进程崩溃后由扫描器收尾的消息使用的错误码。 */
    public static final String STREAM_TIMEOUT = "STREAM_TIMEOUT";
    /** 客户端断开（停止生成）的消息使用的错误码。 */
    public static final String CLIENT_CLOSED = "CLIENT_CLOSED";

    /** 一轮问答开始时确定下来的信息。 */
    public record Turn(long conversationId, ConversationMode mode, Long kbId, long userMessageId,
                       long answerMessageId, List<HistoryRound> history) {
    }

    /** 一轮完整的历史问答。 */
    public record HistoryRound(String question, String answer) {
    }

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final KbPermissionService permissions;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ChatTransactions(ConversationRepository conversations, ChatMessageRepository messages,
                            KbPermissionService permissions, ObjectMapper objectMapper, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.permissions = permissions;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 短事务 1：校验会话归属和知识库权限，读取历史，保存问题和助手消息占位。
     * 任何校验失败都在这里抛出，此时什么都没有写入，接口直接返回 4xx。
     *
     * @param supportedModes 当前能处理的会话模式；不支持时直接拒绝，避免留下无人收尾的占位消息
     */
    @Transactional
    public Turn begin(Actor actor, long conversationId, String question, String traceId, int historyRounds,
                      Set<ConversationMode> supportedModes) {
        Conversation conversation = conversations.findOwned(conversationId, actor.userId())
                .orElseThrow(() -> BusinessException.notFound("会话"));
        if (!supportedModes.contains(conversation.mode())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "暂不支持 " + conversation.mode() + " 模式的会话");
        }
        if (conversation.kbId() != null) {
            permissions.require(actor, conversation.kbId(), KbAccess.READ);
        }
        List<HistoryRound> history = loadHistory(conversationId, historyRounds);
        ChatMessage userMessage = messages.save(ChatMessage.question(conversationId, question, traceId));
        ChatMessage placeholder = messages.save(ChatMessage.answerPlaceholder(conversationId, traceId));
        conversation.onNewQuestion(question);
        return new Turn(conversationId, conversation.mode(), conversation.kbId(), userMessage.id(), placeholder.id(),
                history);
    }

    /**
     * 短事务 2（成功）。返回 false 表示消息已不是 STREAMING（被扫描器收尾），本次写入被放弃。
     *
     * @param toolSteps 没有工具调用时传空列表
     */
    @Transactional
    public boolean finishOk(long messageId, String content, CitationMode mode, List<Citation> citations,
                            List<ToolStep> toolSteps) {
        return lockStreaming(messageId, MessageStatus.OK)
                .map(m -> {
                    m.complete(content, mode, objectMapper.writeValueAsString(citations), json(toolSteps));
                    return true;
                })
                .orElse(false);
    }

    /** 短事务 2（失败）：保留已生成的部分内容和已执行的工具步骤。 */
    @Transactional
    public boolean finishFailed(long messageId, String partialContent, String errorCode, List<ToolStep> toolSteps) {
        return lockStreaming(messageId, MessageStatus.FAILED)
                .map(m -> {
                    m.fail(partialContent, errorCode, json(toolSteps));
                    return true;
                })
                .orElse(false);
    }

    /** 短事务 2（客户端断开）：保留已生成的部分内容和已执行的工具步骤。 */
    @Transactional
    public boolean finishInterrupted(long messageId, String partialContent, List<ToolStep> toolSteps) {
        return lockStreaming(messageId, MessageStatus.INTERRUPTED)
                .map(m -> {
                    m.interrupt(partialContent, CLIENT_CLOSED, json(toolSteps));
                    return true;
                })
                .orElse(false);
    }

    private String json(List<ToolStep> toolSteps) {
        return toolSteps == null || toolSteps.isEmpty() ? null : objectMapper.writeValueAsString(toolSteps);
    }

    /** 把超时仍为 STREAMING 的消息改为 INTERRUPTED，返回处理的条数。 */
    @Transactional
    public int interruptStale(Duration timeout) {
        return messages.interruptStale(clock.instant().minus(timeout), clock.instant(), STREAM_TIMEOUT,
                MessageStatus.STREAMING, MessageStatus.INTERRUPTED);
    }

    private Optional<ChatMessage> lockStreaming(long messageId, MessageStatus target) {
        Optional<ChatMessage> message = messages.findByIdForUpdate(messageId);
        if (message.isPresent() && message.get().status() != MessageStatus.STREAMING) {
            log.info("message {} is already {}, skip finishing it as {}", messageId, message.get().status(), target);
            return Optional.empty();
        }
        return message;
    }

    /** 最近几轮“问题 + 成功的回答”；失败、中断或还在输出的轮次不带给模型。 */
    private List<HistoryRound> loadHistory(long conversationId, int rounds) {
        if (rounds <= 0) {
            return List.of();
        }
        // 多取一些，跳过不完整的轮次后仍能凑够
        List<ChatMessage> latest = new ArrayList<>(
                messages.findByConversationIdOrderByIdDesc(conversationId, Limit.of(rounds * 4)));
        Collections.reverse(latest);
        List<HistoryRound> history = new ArrayList<>();
        for (int i = 0; i + 1 < latest.size(); i++) {
            ChatMessage question = latest.get(i);
            ChatMessage answer = latest.get(i + 1);
            if (question.role() == MessageRole.USER && answer.role() == MessageRole.ASSISTANT
                    && answer.status() == MessageStatus.OK) {
                history.add(new HistoryRound(question.content(), answer.content()));
                i++;
            }
        }
        return history.size() <= rounds ? history : List.copyOf(history.subList(history.size() - rounds,
                history.size()));
    }
}
