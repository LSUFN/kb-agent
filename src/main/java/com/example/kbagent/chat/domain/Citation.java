package com.example.kbagent.chat.domain;

/**
 * 回答引用的一个片段，保存在 chat_message.citations 中，也通过 citations 事件推送给前端（§6.4）。
 *
 * @param index   回答中使用的编号 [n]，即本轮检索结果的序号
 * @param snippet 片段开头的一小段原文，供前端悬浮预览
 */
public record Citation(int index, long documentId, String filename, int page, String snippet, double score) {
}
