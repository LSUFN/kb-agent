package com.example.kbagent.document.service;

import java.time.Instant;

import com.example.kbagent.document.domain.Document;
import com.example.kbagent.document.domain.DocumentStatus;
import com.example.kbagent.document.repository.DocChunkRepository.StoredChunk;
import com.example.kbagent.task.domain.TaskSnapshot;
import com.example.kbagent.task.domain.TaskStatus;

/** 文档接口的响应。 */
public final class DocumentViews {

    private DocumentViews() {
    }

    /**
     * @param errorMessage 面向用户的失败说明；上游原始报错只记录在任务表中，不返回给前端（§4.6）
     * @param task         最近一次入库任务，只在文档详情中返回，供前端轮询和展示重试进度
     */
    public record DocumentView(long id, long kbId, String filename, DocumentStatus status, long sizeBytes,
                               Integer pageCount, int chunkCount, String errorCode, String errorMessage,
                               String embeddingModel, long uploadedBy, Instant createdAt, Instant updatedAt,
                               TaskView task) {

        public static DocumentView of(Document doc, TaskView task) {
            return new DocumentView(doc.id(), doc.kbId(), doc.filename(), doc.status(), doc.sizeBytes(),
                    doc.pageCount(), doc.chunkCount(), doc.errorCode(), doc.errorMessage(), doc.embeddingModel(),
                    doc.uploadedBy(), doc.createdAt(), doc.updatedAt(), task);
        }

        public static DocumentView of(Document doc) {
            return of(doc, null);
        }
    }

    public record TaskView(TaskStatus status, int attempt, int maxAttempts, Instant nextRunAt) {

        public static TaskView of(TaskSnapshot task) {
            return new TaskView(task.status(), task.attempt(), task.maxAttempts(), task.nextRunAt());
        }
    }

    public record ChunkView(int chunkIndex, int pageNumber, int charCount, String content) {

        public static ChunkView of(StoredChunk chunk) {
            return new ChunkView(chunk.chunkIndex(), chunk.pageNumber(), chunk.charCount(), chunk.content());
        }
    }

    /** @param created false 表示知识库中已有内容相同的文档，返回的是已有文档 */
    public record UploadResult(DocumentView document, boolean created) {
    }
}
