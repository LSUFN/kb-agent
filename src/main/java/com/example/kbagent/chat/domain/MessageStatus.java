package com.example.kbagent.chat.domain;

/**
 * 消息状态（§4.3）。用户消息直接是 OK；助手消息先是 STREAMING 占位，结束时变为三个终态之一，终态不能再变化。
 */
public enum MessageStatus {
    STREAMING,
    OK,
    FAILED,
    /** 客户端断开（停止生成），或进程崩溃后被扫描器收尾。 */
    INTERRUPTED;

    public boolean canTransitionTo(MessageStatus target) {
        return this == STREAMING && target != STREAMING;
    }
}
