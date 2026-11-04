package com.example.kbagent.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 检索配置（app.retrieval.*，§4.7）。
 *
 * @param topK     最多取多少个候选片段
 * @param minScore 余弦相似度阈值，低于阈值的片段不作为依据；一个都没有时直接拒答，不调用大模型
 * @param efSearch HNSW 检索时的候选队列长度，越大召回越高、越慢
 */
@ConfigurationProperties("app.retrieval")
public record RetrievalProperties(
        @DefaultValue("5") int topK,
        @DefaultValue("0.5") double minScore,
        @DefaultValue("100") int efSearch) {
}
