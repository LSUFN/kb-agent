package com.example.kbagent.document.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import com.example.kbagent.document.ingest.DocumentTaskTransactions.IngestionTarget;
import com.example.kbagent.storage.StorageService;
import com.example.kbagent.task.domain.ClaimedTask;
import com.example.kbagent.task.domain.TaskType;
import com.example.kbagent.task.service.TaskContext;
import com.example.kbagent.task.service.TaskExceptions.NonRetryableTaskException;
import com.example.kbagent.task.service.TaskHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文档入库（§4.3）：① 短事务领取 → ② 不开事务地解析、切分、调用 Embedding → ③ 短事务提交。
 * 结果在 ② 中暂存在内存里；每处理完一批 Embedding 续租一次，租约被接管或任务被取消时立即停止。
 */
@Component
@EnableConfigurationProperties({IngestionProperties.class, EmbeddingProperties.class})
public class IngestionHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(IngestionHandler.class);

    private final DocumentTaskTransactions transactions;
    private final StorageService storage;
    private final PdfTextExtractor extractor;
    private final EmbeddingClient embeddings;
    private final SentenceAwareTextSplitter splitter;
    private final IngestionProperties ingestionProperties;
    private final EmbeddingProperties embeddingProperties;

    public IngestionHandler(DocumentTaskTransactions transactions, StorageService storage, PdfTextExtractor extractor,
                            EmbeddingClient embeddings, IngestionProperties ingestionProperties,
                            EmbeddingProperties embeddingProperties) {
        this.transactions = transactions;
        this.storage = storage;
        this.extractor = extractor;
        this.embeddings = embeddings;
        this.ingestionProperties = ingestionProperties;
        this.embeddingProperties = embeddingProperties;
        this.splitter = new SentenceAwareTextSplitter(ingestionProperties.chunkSize(),
                ingestionProperties.chunkOverlap());
    }

    @Override
    public TaskType type() {
        return TaskType.INGEST_DOCUMENT;
    }

    @Override
    public void execute(ClaimedTask task, TaskContext context) {
        // ① 短事务：文档改为 PROCESSING
        IngestionTarget target = transactions.startIngestion(task.bizId());

        // ② 不开事务、不占用数据库连接
        List<PageText> pages = readPages(target.storagePath());
        List<TextChunk> chunks = splitter.split(pages);
        if (chunks.isEmpty()) {
            throw new NonRetryableTaskException("NO_EXTRACTABLE_TEXT", "没有可提取的文本，可能是扫描件");
        }
        if (chunks.size() > ingestionProperties.maxChunks()) {
            throw new NonRetryableTaskException("TOO_MANY_CHUNKS",
                    "文档过大：切分后超过 " + ingestionProperties.maxChunks() + " 个片段");
        }
        List<float[]> vectors = new ArrayList<>(chunks.size());
        int batchSize = embeddingProperties.batchSize();
        for (int from = 0; from < chunks.size(); from += batchSize) {
            List<String> batch = chunks.subList(from, Math.min(from + batchSize, chunks.size())).stream()
                    .map(TextChunk::content)
                    .toList();
            vectors.addAll(embeddings.embed(batch));
            context.renewLease();
        }

        // ③ 短事务：fencing 校验 + 写入 chunk + 文档 READY + 任务 SUCCEEDED
        transactions.commitIngestion(context, target, pages.size(), chunks, vectors, embeddingProperties.model());
        log.info("document {} ingested: {} pages, {} chunks", target.documentId(), pages.size(), chunks.size());
    }

    @Override
    public void onGiveUp(ClaimedTask task, String errorCode, String userMessage) {
        transactions.failIngestion(task.bizId(), errorCode, userMessage);
    }

    @Override
    public void onManualRetry(long bizId) {
        transactions.requeueForManualRetry(bizId);
    }

    private List<PageText> readPages(String storagePath) {
        try (InputStream in = storage.open(storagePath)) {
            return extractor.extract(in);
        } catch (UncheckedIOException e) {
            throw new NonRetryableTaskException("FILE_MISSING", "文档文件丢失，请重新上传", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
