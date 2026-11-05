package com.example.kbagent.document.ingest;

import java.util.ArrayList;
import java.util.List;

import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.document.domain.Document;
import com.example.kbagent.document.domain.DocumentStatus;
import com.example.kbagent.document.repository.DocChunkRepository;
import com.example.kbagent.document.repository.DocChunkRepository.NewChunk;
import com.example.kbagent.document.repository.DocumentRepository;
import com.example.kbagent.task.service.TaskContext;
import com.example.kbagent.task.service.TaskExceptions.NonRetryableTaskException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档任务中需要事务的部分（§4.3 的 ① 和 ③）。和调用外部服务的代码分开，
 * 保证 Embedding 调用期间不开事务、不占用数据库连接（ADR-05）。
 * <p>
 * 每个事务都先锁文档行，再改任务表，与删除流程的加锁顺序一致，避免死锁。
 */
@Component
public class DocumentTaskTransactions {

    /** 入库所需的文档信息，在 ① 中读取。 */
    public record IngestionTarget(long documentId, long kbId, String storagePath) {
    }

    private final DocumentRepository documents;
    private final DocChunkRepository chunks;

    public DocumentTaskTransactions(DocumentRepository documents, DocChunkRepository chunks) {
        this.documents = documents;
        this.chunks = chunks;
    }

    /** ① 文档改为 PROCESSING。文档已被删除或正在删除时不再处理。 */
    @Transactional
    public IngestionTarget startIngestion(long documentId) {
        Document doc = documents.findByIdForUpdate(documentId)
                .orElseThrow(() -> new NonRetryableTaskException("DOCUMENT_NOT_FOUND", "文档不存在"));
        if (doc.status() == DocumentStatus.DELETING) {
            throw new NonRetryableTaskException("DOCUMENT_DELETED", "文档已被删除");
        }
        doc.startProcessing();
        return new IngestionTarget(doc.id(), doc.kbId(), doc.storagePath());
    }

    /**
     * ③ fencing 校验 → 写入 chunk → 文档 READY → 任务 SUCCEEDED，在同一个短事务中完成。
     * 计算期间文档被删除时，删除流程已经取消了任务，complete() 会抛出 LeaseLostException，整个事务回滚。
     */
    @Transactional
    public void commitIngestion(TaskContext context, IngestionTarget target, int pageCount,
                                List<TextChunk> textChunks, List<float[]> vectors, String embeddingModel) {
        Document doc = documents.findByIdForUpdate(target.documentId()).orElse(null);
        context.complete();
        if (doc == null || doc.status() != DocumentStatus.PROCESSING) {
            throw new NonRetryableTaskException("DOCUMENT_STATE_CHANGED", "文档状态已变化，本次处理结果作废");
        }
        List<NewChunk> rows = new ArrayList<>(textChunks.size());
        for (TextChunk chunk : textChunks) {
            rows.add(new NewChunk(chunk.index(), chunk.pageNumber(), chunk.content(), vectors.get(chunk.index())));
        }
        chunks.deleteByDocument(doc.id());
        chunks.insertAll(doc.id(), doc.kbId(), rows);
        doc.markReady(pageCount, rows.size(), embeddingModel);
    }

    /** 任务放弃时把文档标记为 FAILED（只处理仍在排队或处理中的文档）。 */
    @Transactional
    public void failIngestion(long documentId, String errorCode, String userMessage) {
        documents.findByIdForUpdate(documentId)
                .filter(doc -> doc.status() == DocumentStatus.PENDING || doc.status() == DocumentStatus.PROCESSING)
                .ifPresent(doc -> doc.markFailed(errorCode, userMessage));
    }

    /** 管理员重试 DEAD 的入库任务：只有 FAILED 的文档需要重新排队。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requeueForManualRetry(long documentId) {
        Document doc = documents.findByIdForUpdate(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "文档已被删除，不需要重试"));
        if (doc.status() != DocumentStatus.FAILED) {
            throw new BusinessException(ErrorCode.CONFLICT, "文档当前状态为 " + doc.status() + "，不需要重试");
        }
        doc.requeue();
    }

    /** 管理员重试 DEAD 的删除任务：文档记录必须还在 DELETING 状态（文件还没删掉）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void checkDeletionPending(long documentId) {
        Document doc = documents.findByIdForUpdate(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "文档已经删除完成，不需要重试"));
        if (doc.status() != DocumentStatus.DELETING) {
            throw new BusinessException(ErrorCode.CONFLICT, "文档当前状态为 " + doc.status() + "，不需要重试");
        }
    }

    /** 文件已删除：完成任务并删除文档记录（chunk 由外键级联删除）。 */
    @Transactional
    public void finishDeletion(TaskContext context, long documentId) {
        var doc = documents.findByIdForUpdate(documentId);
        context.complete();
        doc.filter(d -> d.status() == DocumentStatus.DELETING).ifPresent(documents::delete);
    }
}
