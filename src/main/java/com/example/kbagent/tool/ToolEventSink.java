package com.example.kbagent.tool;

import java.util.Map;

import com.example.kbagent.observability.ToolAuditRecorder.Decision;

/**
 * 工具调用过程的事件出口。agent 模块把它们转成 SSE 的 tool 事件，并记入消息的工具步骤；
 * tool 模块因此不需要依赖 chat 模块。可能在 Reactor 线程上调用，实现必须线程安全。
 */
public interface ToolEventSink {

    /** @param args 已脱敏的参数 */
    void started(String callId, String tool, Map<String, Object> args);

    /**
     * @param result 返回给模型的内容（JSON）。不落库，agent 模块用它做数字核对的出处
     */
    void finished(String callId, String tool, Map<String, Object> args, Decision decision, boolean ok,
                  long latencyMs, String summary, String result);

    ToolEventSink NOOP = new ToolEventSink() {
        @Override
        public void started(String callId, String tool, Map<String, Object> args) {
        }

        @Override
        public void finished(String callId, String tool, Map<String, Object> args, Decision decision, boolean ok,
                             long latencyMs, String summary, String result) {
        }
    };
}
