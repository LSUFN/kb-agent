package com.example.kbagent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.kbagent.user.domain.Role;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** §4.4.3 权限矩阵：角色 × 工具。 */
class ToolPolicyTest {

    private final ToolPolicy policy = new ToolPolicy();

    @ParameterizedTest(name = "{0} 使用 {1}：{2}")
    @CsvSource({
            "USER,    searchKnowledgeBase, true",
            "USER,    queryOrderStats,     false",
            "USER,    countAnomalies,      false",
            "USER,    generateReport,      false",
            "ANALYST, searchKnowledgeBase, true",
            "ANALYST, queryOrderStats,     true",
            "ANALYST, countAnomalies,      true",
            "ANALYST, generateReport,      true",
            "ADMIN,   searchKnowledgeBase, true",
            "ADMIN,   queryOrderStats,     true",
            "ADMIN,   countAnomalies,      true",
            "ADMIN,   generateReport,      true",
            "ADMIN,   dropAllTables,       false"
    })
    void matrix(Role role, String tool, boolean allowed) {
        assertThat(policy.allows(role, tool)).isEqualTo(allowed);
    }
}
