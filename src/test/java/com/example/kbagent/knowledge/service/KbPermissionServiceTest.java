package com.example.kbagent.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import com.example.kbagent.knowledge.domain.KbAccess;
import com.example.kbagent.knowledge.domain.KbPermission;
import com.example.kbagent.knowledge.domain.KnowledgeBase;
import com.example.kbagent.knowledge.domain.Visibility;
import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.domain.Region;
import com.example.kbagent.user.domain.Role;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

/** 知识库访问级别矩阵：角色 × 所有权 × 成员权限 × 可见性。 */
class KbPermissionServiceTest {

    private static final long OWNER_ID = 10L;
    private static final long OTHER_ID = 20L;

    @ParameterizedTest(name = "{0} / 所有者={1} / 成员={2} / {3} → {4}")
    @CsvSource(nullValues = "-", value = {
            "ADMIN,   false, -,      PRIVATE, MANAGE",
            "USER,    true,  -,      PRIVATE, MANAGE",
            "ANALYST, true,  -,      PUBLIC,  MANAGE",
            "USER,    false, EDITOR, PRIVATE, EDIT",
            "USER,    false, EDITOR, PUBLIC,  EDIT",
            "USER,    false, READER, PRIVATE, READ",
            "USER,    false, -,      PUBLIC,  READ",
            "ANALYST, false, -,      PRIVATE, NONE",
            "USER,    false, -,      PRIVATE, NONE"})
    void accessMatrix(Role role, boolean owner, KbPermission membership, Visibility visibility, KbAccess expected) {
        Actor actor = new Actor(owner ? OWNER_ID : OTHER_ID, "u", "U", role,
                role == Role.ANALYST ? Set.of(Region.EAST) : Set.of());
        KnowledgeBase kb = KnowledgeBase.create("制度库", null, OWNER_ID, visibility);
        ReflectionTestUtils.setField(kb, "id", 1L);

        assertThat(KbPermissionService.accessOf(actor, kb, membership)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"MANAGE, EDIT, true", "EDIT, READ, true", "READ, EDIT, false", "NONE, READ, false", "EDIT, EDIT, true"})
    void accessLevelsAreCumulative(KbAccess granted, KbAccess required, boolean expected) {
        assertThat(granted.includes(required)).isEqualTo(expected);
    }
}
