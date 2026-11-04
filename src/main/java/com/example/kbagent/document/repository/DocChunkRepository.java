package com.example.kbagent.document.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * doc_chunk 的写入与分页查看（ADR-02：自建表，只使用 Spring AI 的 EmbeddingModel）。
 * 向量检索在 M3 的 retrieval 模块中实现。
 */
@Repository
public class DocChunkRepository {

    private static final int BATCH_SIZE = 200;

    /** 写入一个 chunk 所需的数据。 */
    public record NewChunk(int chunkIndex, int pageNumber, String content, float[] embedding) {
    }

    public record StoredChunk(int chunkIndex, int pageNumber, int charCount, String content) {
    }

    private final JdbcTemplate jdbc;

    public DocChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertAll(long documentId, long kbId, List<NewChunk> chunks) {
        jdbc.batchUpdate("""
                        INSERT INTO doc_chunk(document_id, kb_id, chunk_index, page_number, content, char_count, embedding)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS vector))""",
                chunks, BATCH_SIZE, (ps, chunk) -> {
                    ps.setLong(1, documentId);
                    ps.setLong(2, kbId);
                    ps.setInt(3, chunk.chunkIndex());
                    ps.setInt(4, chunk.pageNumber());
                    ps.setString(5, chunk.content());
                    ps.setInt(6, chunk.content().length());
                    ps.setString(7, toVectorLiteral(chunk.embedding()));
                });
    }

    public int deleteByDocument(long documentId) {
        return jdbc.update("DELETE FROM doc_chunk WHERE document_id = ?", documentId);
    }

    public long countByDocument(long documentId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM doc_chunk WHERE document_id = ?", Long.class,
                documentId);
        return count == null ? 0 : count;
    }

    public List<StoredChunk> page(long documentId, int offset, int limit) {
        return jdbc.query("""
                        SELECT chunk_index, page_number, char_count, content FROM doc_chunk
                         WHERE document_id = ? ORDER BY chunk_index OFFSET ? LIMIT ?""",
                (rs, n) -> new StoredChunk(rs.getInt("chunk_index"), rs.getInt("page_number"),
                        rs.getInt("char_count"), rs.getString("content")),
                documentId, offset, limit);
    }

    /** pgvector 的文本格式：[0.1,0.2,...]。 */
    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
