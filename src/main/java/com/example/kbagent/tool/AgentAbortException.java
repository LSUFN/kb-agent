package com.example.kbagent.tool;

import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;

/**
 * 工具调用尝试次数达到上限，终止本轮（§4.4.2）。
 * <p>
 * S4 实测：只有在 ToolCallback 包装层、调用被包装工具之前抛出的普通 RuntimeException 才能终止工具循环；
 * 所以它只继承 RuntimeException（经由 BusinessException），不能继承 Spring AI 的 ToolExecutionException——
 * 后者会被框架转成消息交给模型，循环不会停。stream() 以 onError 结束后，由 ChatRound 转成 error 事件。
 */
public class AgentAbortException extends BusinessException {

    public AgentAbortException(int attempts) {
        super(ErrorCode.AGENT_ABORTED, ErrorCode.AGENT_ABORTED.defaultMessage() + "（已尝试 " + attempts + " 次）");
    }
}
