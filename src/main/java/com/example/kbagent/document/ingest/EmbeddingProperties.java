package com.example.kbagent.document.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Embedding 配置（app.embedding.*）。
 *
 * @param dimensions 必须等于 doc_chunk.embedding 的列维度，启动时校验（§2.1 风险 3）
 * @param batchSize  每次请求提交的文本数；通义 text-embedding-v3 单次最多 10 条（Spike S3）
 * @param model      写入 document.embedding_model，为以后更换模型后重新向量化做准备
 */
@ConfigurationProperties("app.embedding")
public record EmbeddingProperties(
        @DefaultValue("1024") int dimensions,
        @DefaultValue("10") int batchSize,
        @DefaultValue("text-embedding-v3") String model) {
}
