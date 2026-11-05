package com.example.kbagent.tool;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.example.kbagent.user.domain.Role;
import org.springframework.stereotype.Component;

/**
 * 工具权限矩阵（§4.4.3）。GuardedToolCallback 每次执行前都按角色检查，
 * 不依赖“只向模型暴露了有权限的工具”——暴露过滤只是为了减少无效尝试，不是安全边界。
 * 数据范围（区域、知识库）的检查在各工具的 narrow 中完成。
 */
@Component
public class ToolPolicy {

    public static final String SEARCH_KNOWLEDGE_BASE = "searchKnowledgeBase";
    public static final String QUERY_ORDER_STATS = "queryOrderStats";
    public static final String COUNT_ANOMALIES = "countAnomalies";
    public static final String GENERATE_REPORT = "generateReport";

    private static final Map<String, Set<Role>> MATRIX = Map.of(
            SEARCH_KNOWLEDGE_BASE, EnumSet.of(Role.USER, Role.ANALYST, Role.ADMIN),
            QUERY_ORDER_STATS, EnumSet.of(Role.ANALYST, Role.ADMIN),
            COUNT_ANOMALIES, EnumSet.of(Role.ANALYST, Role.ADMIN),
            GENERATE_REPORT, EnumSet.of(Role.ANALYST, Role.ADMIN));

    /** 未登记的工具一律拒绝（fail-closed）。 */
    public boolean allows(Role role, String toolName) {
        Set<Role> roles = MATRIX.get(toolName);
        return roles != null && roles.contains(role);
    }
}
