package com.example.kbagent.chat.domain;

import java.util.Map;

/**
 * AGENT 模式下一次工具调用的记录，保存在 chat_message.tool_steps 中，历史消息里可以回看工具调用过程。
 *
 * @param args     已脱敏的参数
 * @param decision ALLOWED / DENIED / INVALID / BUDGET_EXCEEDED
 * @param ok       工具是否执行成功
 * @param summary  结果摘要（行数、合计值），不含明细
 */
public record ToolStep(String callId, String tool, Map<String, Object> args, String decision, boolean ok,
                       long latencyMs, String summary) {
}
