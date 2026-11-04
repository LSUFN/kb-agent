package com.example.kbagent.chat.domain;

public enum ConversationMode {
    /** 先检索后生成，回答带引用（M3）。 */
    KB_QA,
    /** 工具调用（M4）。 */
    AGENT
}
