package com.example.kbagent.document.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 入库配置（app.ingestion.*，§4.3、§4.7）。
 *
 * @param maxChunks 单个文档的 chunk 上限；入库结果暂存在内存中（1024 维约 12MB / 3000 个），超出则判为失败
 */
@ConfigurationProperties("app.ingestion")
public record IngestionProperties(
        @DefaultValue("500") int chunkSize,
        @DefaultValue("80") int chunkOverlap,
        @DefaultValue("3000") int maxChunks) {
}
