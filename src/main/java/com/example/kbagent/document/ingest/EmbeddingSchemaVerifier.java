package com.example.kbagent.document.ingest;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 启动时校验“配置的 Embedding 维度 = doc_chunk.embedding 的列维度”，不一致就拒绝启动（§2.1 风险 3）。
 * 否则要等到第一个文档入库、写入向量时才会报错。
 */
@Component
class EmbeddingSchemaVerifier implements InitializingBean {

    private final JdbcClient jdbc;
    private final EmbeddingProperties properties;

    EmbeddingSchemaVerifier(JdbcClient jdbc, EmbeddingProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        // pgvector 把 vector(n) 的维度存放在 atttypmod 中
        int columnDimensions = jdbc.sql("""
                        SELECT atttypmod FROM pg_attribute
                         WHERE attrelid = 'doc_chunk'::regclass AND attname = 'embedding'""")
                .query(Integer.class)
                .single();
        if (columnDimensions != properties.dimensions()) {
            throw new IllegalStateException(("doc_chunk.embedding 的维度是 %d，但配置的 Embedding 维度"
                    + "（AI_EMBEDDING_DIMENSIONS）是 %d。修改维度需要新的 Flyway 迁移并重新向量化全部文档")
                    .formatted(columnDimensions, properties.dimensions()));
        }
    }
}
