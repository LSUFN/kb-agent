package com.example.kbagent.chat.service;

import com.example.kbagent.chat.domain.ConversationMode;
import com.example.kbagent.chat.service.ChatTransactions.Turn;
import com.example.kbagent.user.domain.Actor;
import reactor.core.publisher.Flux;

/**
 * 某一种会话模式的回答流程。chat 模块通过这个接口分发，不直接依赖实现方：
 * KB_QA 由本模块的 {@link KbQaService} 实现，AGENT 由 agent 模块实现，依赖方向始终是 agent → chat。
 */
public interface ChatModeHandler {

    ConversationMode mode();

    /**
     * 短事务 1 已经完成之后调用。返回冷的 Flux，不以错误结束（失败转换为 error 事件），
     * 被取消时消息标记为 INTERRUPTED。通常就是某个 {@link ChatRound} 的 {@code events()}。
     */
    Flux<ChatEvent> answer(Actor actor, Turn turn, String question, String traceId);
}
