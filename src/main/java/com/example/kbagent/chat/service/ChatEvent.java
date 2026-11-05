package com.example.kbagent.chat.service;

import java.util.List;
import java.util.Map;

import com.example.kbagent.chat.domain.Citation;
import com.example.kbagent.chat.domain.CitationMode;
import com.example.kbagent.chat.domain.MessageStatus;
import com.example.kbagent.retrieval.service.RetrievalService.Candidate;

/**
 * 一轮问答中推送给客户端的事件（§6.4）。record 的字段就是 SSE data 中的 JSON 字段。
 * 正常顺序：KB_QA 为 meta → retrieval → delta… → citations → done；AGENT 为 meta → (tool START → tool END)…
 * → delta… → citations → done，工具调用和文本可以交替出现。失败时以 error 结束。
 */
public sealed interface ChatEvent {

    /** SSE 的 event 名称。 */
    default String eventName() {
        return switch (this) {
            case Meta m -> "meta";
            case Retrieval r -> "retrieval";
            case ToolStart t -> "tool";
            case ToolEnd t -> "tool";
            case Delta d -> "delta";
            case Citations c -> "citations";
            case Done d -> "done";
            case Error e -> "error";
        };
    }

    record Meta(long messageId, long userMessageId, String traceId) implements ChatEvent {
    }

    /** 达到阈值的检索结果；为空表示将直接拒答。 */
    record Retrieval(List<RetrievedCandidate> candidates) implements ChatEvent {

        static Retrieval of(List<Candidate> candidates) {
            return new Retrieval(candidates.stream()
                    .map(c -> new RetrievedCandidate(c.index(), c.documentId(), c.filename(), c.pageNumber(),
                            c.score()))
                    .toList());
        }
    }

    record RetrievedCandidate(int index, long documentId, String filename, int page, double score) {
    }

    /**
     * 开始执行一次工具调用。
     *
     * @param phase 固定为 START
     * @param args  已脱敏的参数
     */
    record ToolStart(String callId, String name, String phase, Map<String, Object> args) implements ChatEvent {

        public ToolStart(String callId, String name, Map<String, Object> args) {
            this(callId, name, "START", args);
        }
    }

    /**
     * 一次工具调用结束（包括被拒绝、参数不合法、超出预算）。
     *
     * @param phase    固定为 END
     * @param decision ALLOWED / DENIED / INVALID / BUDGET_EXCEEDED
     * @param ok       工具是否执行成功
     * @param summary  结果摘要，例如“共 37 条，按类型分 4 组”
     */
    record ToolEnd(String callId, String name, String phase, String decision, boolean ok, long latencyMs,
                   String summary) implements ChatEvent {

        public ToolEnd(String callId, String name, String decision, boolean ok, long latencyMs, String summary) {
            this(callId, name, "END", decision, ok, latencyMs, summary);
        }
    }

    record Delta(String text) implements ChatEvent {
    }

    /** @param invalidIndices 回答中出现、但不对应任何检索结果的编号；前端把它们当普通文本显示 */
    record Citations(CitationMode mode, List<Citation> citations, List<Integer> invalidIndices)
            implements ChatEvent {
    }

    /**
     * @param usage        没有调用模型（拒答）时为 null
     * @param latencyMs    从开始处理到结束的总耗时
     * @param firstTokenMs 从开始处理到收到模型第一个字的耗时；没有调用模型时为 null
     */
    record Done(MessageStatus status, Usage usage, long latencyMs, Integer firstTokenMs) implements ChatEvent {
    }

    /** @param estimated 模型没有返回用量时按字数估算 */
    record Usage(Integer prompt, Integer completion, boolean estimated) {
    }

    /** message 是面向用户的通用描述，不包含上游的原始报错。 */
    record Error(String code, String message, String traceId) implements ChatEvent {
    }
}
