package com.example.kbagent.knowledge.repository;

import java.time.Instant;
import java.util.List;

import com.example.kbagent.knowledge.domain.KbMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface KbMemberRepository extends JpaRepository<KbMember, KbMember.Key> {

    /** 成员及其用户名、显示名。 */
    interface MemberRow {
        long getUserId();

        String getUsername();

        String getDisplayName();

        String getPermission();

        Instant getCreatedAt();
    }

    @Query(value = """
            SELECT m.user_id AS "userId", u.username AS "username", u.display_name AS "displayName",
                   m.permission AS "permission", m.created_at AS "createdAt"
              FROM kb_member m JOIN sys_user u ON u.id = m.user_id
             WHERE m.kb_id = :kbId
             ORDER BY m.created_at, m.user_id
            """, nativeQuery = true)
    List<MemberRow> findMemberRows(long kbId);

    /** 某个用户的全部成员关系，用于一次性计算知识库列表中每一项的访问级别。 */
    @Query("select m from KbMember m where m.id.userId = :userId")
    List<KbMember> findByUserId(long userId);
}
