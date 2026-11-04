package com.example.kbagent.retrieval.repository;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.example.kbagent.common.vector.VectorLiterals;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 带权限过滤的向量检索（ADR-02：权限过滤就是普通的 SQL 条件）。 */
@Repository
public class ChunkVectorRepository {

    /** @param score 余弦相似度，1 表示方向完全一致 */
    public record ChunkHit(long chunkId, long documentId, String filename, int pageNumber, int chunkIndex,
                           String content, double score) {
    }

    private final JdbcClient jdbc;

    public ChunkVectorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 在给定知识库中取与 query 最接近的 topK 个片段，只包含 READY 文档。
     * <p>
     * HNSW 先按距离取候选再做过滤，过滤后可能凑不满 topK；pgvector 0.8 的迭代扫描会继续往下找。
     * {@code SET LOCAL} 只在当前事务内生效，所以这个方法必须在事务中执行。
     */
    @Transactional(readOnly = true)
    public List<ChunkHit> search(float[] query, Collection<Long> kbIds, int topK, int efSearch) {
        if (kbIds.isEmpty()) {
            return List.of();
        }
        jdbc.sql("SET LOCAL hnsw.iterative_scan = relaxed_order").update();
        jdbc.sql("SET LOCAL hnsw.ef_search = " + Math.max(efSearch, topK)).update();
        List<ChunkHit> hits = jdbc.sql("""
                        SELECT c.id, c.document_id, d.filename, c.page_number, c.chunk_index, c.content,
                               1 - (c.embedding <=> CAST(:vector AS vector)) AS score
                          FROM doc_chunk c JOIN document d ON d.id = c.document_id
                         WHERE c.kb_id IN (:kbIds) AND d.status = 'READY'
                         ORDER BY c.embedding <=> CAST(:vector AS vector)
                         LIMIT :topK""")
                .param("vector", VectorLiterals.toLiteral(query))
                .param("kbIds", kbIds)
                .param("topK", topK)
                .query((rs, n) -> new ChunkHit(rs.getLong("id"), rs.getLong("document_id"), rs.getString("filename"),
                        rs.getInt("page_number"), rs.getInt("chunk_index"), rs.getString("content"),
                        rs.getDouble("score")))
                .list();
        // relaxed_order 下结果可能略微乱序，按相似度重新排序
        return hits.stream().sorted(Comparator.comparingDouble(ChunkHit::score).reversed()).toList();
    }
}
