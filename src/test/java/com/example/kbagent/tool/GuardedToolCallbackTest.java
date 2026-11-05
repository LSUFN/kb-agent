package com.example.kbagent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.Validation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;

import com.example.kbagent.business.service.DataScope;
import com.example.kbagent.observability.Sensitive;
import com.example.kbagent.observability.ToolAuditRecorder;
import com.example.kbagent.observability.ToolAuditRecorder.Decision;
import com.example.kbagent.support.TestActors;
import com.example.kbagent.user.domain.Actor;
import com.example.kbagent.user.domain.Region;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** §4.4.3 GuardedToolCallback 的固定执行顺序。被包装的工具是一个记录调用情况的假工具。 */
class GuardedToolCallbackTest {

    record Args(
            @ToolParam(required = false, description = "区域") Region region,
            @ToolParam(description = "条数") @NotNull @Max(100) Integer count,
            @ToolParam(required = false, description = "备注") @Sensitive String note) {
    }

    /** 借用 queryOrderStats 的名字，使用它在权限矩阵中的规则（ANALYST、ADMIN 可用）。 */
    static final class FakeTool implements AgentTool<Args> {

        final List<Args> executed = new ArrayList<>();
        Set<Region> lastScope;
        RuntimeException failure;

        @Override
        public String name() {
            return ToolPolicy.QUERY_ORDER_STATS;
        }

        @Override
        public String description() {
            return "测试工具";
        }

        @Override
        public Class<Args> argsType() {
            return Args.class;
        }

        @Override
        public String validate(Args args) {
            return args.count() == 13 ? "count 不能是 13" : null;
        }

        @Override
        public Set<Region> narrow(Args args, AgentRequestContext ctx) {
            return DataScope.narrow(ctx.actor(), args.region());
        }

        @Override
        public Output execute(Args args, Set<Region> scope, AgentRequestContext ctx) {
            if (failure != null) {
                throw failure;
            }
            executed.add(args);
            lastScope = scope;
            return new Output(Map.of("count", args.count()), "共 " + args.count() + " 条");
        }
    }

    private final ObjectMapper json = JsonMapper.builder().build();
    private final ToolAuditRecorder audit = mock(ToolAuditRecorder.class);
    private final List<String> events = new ArrayList<>();
    private FakeTool tool;
    private GuardedToolCallback<Args> callback;

    @BeforeEach
    void setUp() {
        tool = new FakeTool();
        callback = new GuardedToolCallback<>(tool, new ToolPolicy(),
                Validation.buildDefaultValidatorFactory().getValidator(), json, audit);
    }

    @Test
    void withoutServerContextNothingRuns() {
        String result = callback.call("{\"count\":1}", new ToolContext(Map.of()));

        assertThat(error(result)).isEqualTo("NO_CONTEXT");
        assertThat(callback.call("{\"count\":1}")).contains("NO_CONTEXT");
        assertThat(tool.executed).isEmpty();
        verify(audit, never()).record(any());
    }

    @Test
    void roleIsCheckedEvenIfTheToolWasNotExposed() {
        ToolBudget budget = new ToolBudget(2, 4);

        String result = callback.call("{\"count\":1}", context(TestActors.USER, budget));

        assertThat(error(result)).isEqualTo("FORBIDDEN_TOOL");
        assertThat(tool.executed).isEmpty();
        assertThat(budget.executions()).isZero();
        assertThat(events).containsExactly("START c1 queryOrderStats {}", "END c1 DENIED false");
        assertThat(audited().decision()).isEqualTo(Decision.DENIED);
        assertThat(audited().errorCode()).isEqualTo("FORBIDDEN_TOOL");
    }

    @Test
    void malformedOrInvalidArgumentsAreRejectedBeforeExecution() {
        assertThat(error(callback.call("{\"count\":", context(TestActors.ANALYST, new ToolBudget(2, 9)))))
                .isEqualTo("INVALID_ARGUMENTS");
        assertThat(message(callback.call("{\"note\":\"x\"}", context(TestActors.ANALYST, new ToolBudget(2, 9)))))
                .contains("count");
        assertThat(message(callback.call("{\"count\":101}", context(TestActors.ANALYST, new ToolBudget(2, 9)))))
                .contains("count");
        assertThat(message(callback.call("{\"count\":13}", context(TestActors.ANALYST, new ToolBudget(2, 9)))))
                .isEqualTo("count 不能是 13");
        assertThat(tool.executed).isEmpty();
    }

    @Test
    void regionOutsideTheScopeIsDenied() {
        String result = callback.call("{\"count\":1,\"region\":\"NORTH\"}",
                context(TestActors.ANALYST, new ToolBudget(2, 4)));

        assertThat(error(result)).isEqualTo("FORBIDDEN_SCOPE");
        assertThat(message(result)).contains("NORTH");
        assertThat(tool.executed).isEmpty();
        assertThat(audited().decision()).isEqualTo(Decision.DENIED);
    }

    @Test
    void allowedCallRunsWithTheNarrowedScopeAndMaskedArguments() {
        String result = callback.call("{\"count\":3,\"region\":\"east\",\"note\":\"手机 13812345678\"}",
                context(TestActors.ANALYST, new ToolBudget(2, 4)));

        assertThat(json.readTree(result).path("count").asInt()).isEqualTo(3);
        assertThat(tool.lastScope).containsExactly(Region.EAST);
        assertThat(events).containsExactly(
                "START c1 queryOrderStats {region=EAST, count=3, note=手机 138****5678}",
                "END c1 ALLOWED true");
        ToolAuditRecorder.Entry entry = audited();
        assertThat(entry.decision()).isEqualTo(Decision.ALLOWED);
        assertThat(entry.success()).isTrue();
        assertThat(entry.effectiveScope()).containsExactly("EAST");
        assertThat(entry.maskedArgs()).containsEntry("note", "手机 138****5678");
        assertThat(entry.resultSummary()).isEqualTo("共 3 条");
        assertThat(entry.userRole()).isEqualTo("ANALYST");
        assertThat(entry.traceId()).isEqualTo("trace-1");
    }

    @Test
    void budgetIsConsumedOnlyByExecutionsAndExceededCallsDoNotRun() {
        ToolBudget budget = new ToolBudget(2, 9);
        ToolContext ctx = context(TestActors.ANALYST, budget);

        callback.call("{\"count\":1}", ctx);
        callback.call("{\"count\":1,\"region\":\"NORTH\"}", ctx); // 被拒绝，不占用预算
        callback.call("{\"count\":1}", ctx);
        String fourth = callback.call("{\"count\":1}", ctx);

        assertThat(error(fourth)).isEqualTo("BUDGET_EXCEEDED");
        assertThat(tool.executed).hasSize(2);
        assertThat(events).last().isEqualTo("END c4 BUDGET_EXCEEDED false");
    }

    @Test
    void reachingTheAttemptLimitAbortsBeforeTheToolRuns() {
        ToolContext ctx = context(TestActors.ANALYST, new ToolBudget(1, 3));
        callback.call("{\"count\":1}", ctx);
        callback.call("{\"count\":1}", ctx);

        assertThatThrownBy(() -> callback.call("{\"count\":1}", ctx)).isInstanceOf(AgentAbortException.class);
        assertThat(tool.executed).hasSize(1);
        ArgumentCaptor<ToolAuditRecorder.Entry> entries = ArgumentCaptor.forClass(ToolAuditRecorder.Entry.class);
        verify(audit, org.mockito.Mockito.times(3)).record(entries.capture());
        assertThat(entries.getValue().errorCode()).isEqualTo("AGENT_ABORTED");
    }

    @Test
    void executionFailureBecomesAToolErrorForTheModel() {
        tool.failure = new IllegalStateException("db down");

        String result = callback.call("{\"count\":1}", context(TestActors.ANALYST, new ToolBudget(2, 4)));

        assertThat(error(result)).isEqualTo("TOOL_ERROR");
        assertThat(message(result)).doesNotContain("db down");
        assertThat(audited().decision()).isEqualTo(Decision.ALLOWED);
        assertThat(audited().success()).isFalse();
        assertThat(events).last().isEqualTo("END c1 ALLOWED false");
    }

    @Test
    void schemaComesFromTheArgsRecord() {
        JsonNode schema = json.readTree(callback.getToolDefinition().inputSchema());

        assertThat(callback.getToolDefinition().name()).isEqualTo("queryOrderStats");
        assertThat(schema.path("properties").path("count").path("description").asString()).isEqualTo("条数");
        assertThat(schema.path("properties").path("region").path("enum").size()).isEqualTo(5);
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(n -> required.add(n.asString()));
        assertThat(required).containsExactly("count");
    }

    // ---------------------------------------------------------------------

    private ToolContext context(Actor actor, ToolBudget budget) {
        ToolEventSink sink = new ToolEventSink() {
            @Override
            public void started(String callId, String name, Map<String, Object> args) {
                events.add("START " + callId + " " + name + " " + args);
            }

            @Override
            public void finished(String callId, String name, Map<String, Object> args, Decision decision, boolean ok,
                                 long latencyMs, String summary) {
                events.add("END " + callId + " " + decision + " " + ok);
            }
        };
        return new ToolContext(new AgentRequestContext(actor, List.of(1L), 5L, "trace-1", budget, sink,
                new CitationRegistry()).asToolContext());
    }

    private ToolAuditRecorder.Entry audited() {
        ArgumentCaptor<ToolAuditRecorder.Entry> entry = ArgumentCaptor.forClass(ToolAuditRecorder.Entry.class);
        verify(audit, org.mockito.Mockito.atLeastOnce()).record(entry.capture());
        return entry.getValue();
    }

    private String error(String result) {
        return json.readTree(result).path("error").asString();
    }

    private String message(String result) {
        return json.readTree(result).path("message").asString();
    }
}
