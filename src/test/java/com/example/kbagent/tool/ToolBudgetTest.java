package com.example.kbagent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** §4.4.2 调用预算：最多执行 maxCalls 次；累计尝试达到 maxAttempts 次时终止。 */
class ToolBudgetTest {

    @Test
    void executionsAreCappedButAttemptsKeepCounting() {
        ToolBudget budget = new ToolBudget(6, 10);
        for (int i = 0; i < 6; i++) {
            budget.recordAttempt();
            assertThat(budget.tryConsume()).isTrue();
        }
        budget.recordAttempt();
        assertThat(budget.tryConsume()).isFalse();
        assertThat(budget.executions()).isEqualTo(6);
        assertThat(budget.attempts()).isEqualTo(7);
    }

    @Test
    void theTenthAttemptAborts() {
        ToolBudget budget = new ToolBudget(6, 10);
        for (int i = 0; i < 9; i++) {
            budget.recordAttempt();
        }
        assertThatThrownBy(budget::recordAttempt)
                .isInstanceOf(AgentAbortException.class)
                .hasMessageContaining("10 次");
    }

    @Test
    void maxAttemptsMustExceedMaxCalls() {
        assertThatThrownBy(() -> new ToolBudget(6, 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolBudget(0, 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
