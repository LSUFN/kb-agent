package com.example.kbagent.document.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 文档状态机。
 * <pre>
 * PENDING ──▶ PROCESSING ──▶ READY
 *    │            │  ▲          │
 *    │            ▼  │(重新领取) │ 重新处理
 *    │          FAILED ◀────────┼──────▶ PENDING
 *    └────────────┴─────────────┴──────▶ DELETING（终态，随后记录被删除）
 * </pre>
 */
public enum DocumentStatus {

    PENDING,
    PROCESSING,
    READY,
    FAILED,
    DELETING;

    private static final Map<DocumentStatus, Set<DocumentStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(PROCESSING, FAILED, DELETING),
            // PROCESSING → PROCESSING：执行者中途死亡，任务被重新领取
            PROCESSING, EnumSet.of(PROCESSING, READY, FAILED, DELETING),
            READY, EnumSet.of(PENDING, DELETING),
            FAILED, EnumSet.of(PENDING, DELETING),
            DELETING, EnumSet.noneOf(DocumentStatus.class));

    public boolean canTransitionTo(DocumentStatus target) {
        return ALLOWED.get(this).contains(target);
    }
}
