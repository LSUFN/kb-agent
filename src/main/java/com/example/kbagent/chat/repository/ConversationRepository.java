package com.example.kbagent.chat.repository;

import java.util.Optional;

import com.example.kbagent.chat.domain.Conversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /** 只返回本人未删除的会话；不属于本人的会话与不存在的会话不作区分（都返回 404）。 */
    @Query("select c from Conversation c where c.id = :id and c.userId = :userId and c.deleted = false")
    Optional<Conversation> findOwned(long id, long userId);

    @Query("select c from Conversation c where c.userId = :userId and c.deleted = false")
    Page<Conversation> findOwnedPage(long userId, Pageable pageable);

    /** @param pattern 已转义的 LIKE 模式（小写），例如 {@code %差旅%} */
    @Query("""
            select c from Conversation c
            where c.userId = :userId and c.deleted = false and lower(c.title) like :pattern escape '\\'""")
    Page<Conversation> findOwnedPageByTitle(long userId, String pattern, Pageable pageable);
}
