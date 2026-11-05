package com.example.kbagent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.kbagent.retrieval.service.RetrievalService.Candidate;

/**
 * 一轮 AGENT 对话的引用编号表（§4.4.4）。searchKnowledgeBase 可能被调用多次，编号由这里全局分配：
 * 第一次检索得到 [1]～[3]，第二次检索的新片段从 [4] 开始；同一个片段再次出现时沿用原来的编号。
 * 最终解析回答中的引用时，以这张表为准。
 */
public final class CitationRegistry {

    private final Map<Long, Candidate> byChunkId = new LinkedHashMap<>();

    /** 登记一次检索的结果，返回按全局编号重新编号后的片段（顺序与输入一致）。 */
    public synchronized List<Candidate> register(List<Candidate> found) {
        List<Candidate> numbered = new ArrayList<>(found.size());
        for (Candidate c : found) {
            Candidate registered = byChunkId.computeIfAbsent(c.chunkId(), id -> new Candidate(byChunkId.size() + 1,
                    c.chunkId(), c.documentId(), c.filename(), c.pageNumber(), c.chunkIndex(), c.content(),
                    c.score()));
            numbered.add(registered);
        }
        return numbered;
    }

    /** 本轮登记过的全部片段，按编号排列。 */
    public synchronized List<Candidate> all() {
        return List.copyOf(byChunkId.values());
    }
}
