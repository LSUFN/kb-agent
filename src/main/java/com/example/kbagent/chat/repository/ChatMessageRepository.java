package com.example.kbagent.chat.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import com.example.kbagent.chat.domain.ChatMessage;
import com.example.kbagent.chat.domain.MessageStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** SELECT ... FOR UPDATE：收尾时与超时扫描器互斥，保证只有一方能把 STREAMING 改成终态。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ChatMessage m where m.id = :id")
    Optional<ChatMessage> findByIdForUpdate(long id);

    /** 最新的若干条消息，按 id 倒序。 */
    List<ChatMessage> findByConversationIdOrderByIdDesc(long conversationId, Limit limit);

    /** 游标分页：id 小于 beforeId 的若干条消息，按 id 倒序。 */
    List<ChatMessage> findByConversationIdAndIdLessThanOrderByIdDesc(long conversationId, long beforeId, Limit limit);

    /** 把超时仍为 STREAMING 的消息改为 INTERRUPTED（进程崩溃时没有机会收尾，§4.3）。 */
    @Modifying
    @Query("""
            update ChatMessage m set m.status = :interrupted, m.errorCode = :errorCode, m.updatedAt = :now
            where m.status = :streaming and m.updatedAt < :cutoff""")
    int interruptStale(Instant cutoff, Instant now, String errorCode, MessageStatus streaming,
                       MessageStatus interrupted);
}
