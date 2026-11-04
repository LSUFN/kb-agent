package com.example.kbagent.document.ingest;

/** 切分结果。index 在整个文档内连续编号；一个 chunk 只属于一页。 */
public record TextChunk(int index, int pageNumber, String content) {

    public int charCount() {
        return content.length();
    }
}
