package com.example.kbagent.chat.domain;

/** 回答的引用情况（§4.5）。 */
public enum CitationMode {
    /** 回答中至少有一个合法引用。 */
    NORMAL,
    /** 检索到了资料，但回答里没有合法引用；前端把检索到的资料作为“参考资料”展示。 */
    FALLBACK,
    /** 没有检索到达到阈值的资料，直接拒答，没有调用模型。 */
    NONE
}
