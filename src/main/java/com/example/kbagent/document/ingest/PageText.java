package com.example.kbagent.document.ingest;

/** PDF 某一页提取出的文本。页码从 1 开始。 */
public record PageText(int pageNumber, String text) {
}
