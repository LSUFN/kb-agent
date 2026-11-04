package com.example.kbagent.knowledge.repository;

import com.example.kbagent.knowledge.domain.KnowledgeBase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    /** 非 ADMIN 用户可访问的知识库：自己拥有的、公开的、自己是成员的。 */
    @Query(value = """
            SELECT kb.* FROM knowledge_base kb
             WHERE kb.owner_id = :userId
                OR kb.visibility = 'PUBLIC'
                OR EXISTS (SELECT 1 FROM kb_member m WHERE m.kb_id = kb.id AND m.user_id = :userId)
            """,
            countQuery = """
            SELECT count(*) FROM knowledge_base kb
             WHERE kb.owner_id = :userId
                OR kb.visibility = 'PUBLIC'
                OR EXISTS (SELECT 1 FROM kb_member m WHERE m.kb_id = kb.id AND m.user_id = :userId)
            """,
            nativeQuery = true)
    Page<KnowledgeBase> findAccessible(long userId, Pageable pageable);

    /** 包括正在删除中的文档：文档全部删除完毕后知识库才能删除。 */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM document WHERE kb_id = :kbId)", nativeQuery = true)
    boolean hasDocuments(long kbId);
}
