package com.example.kbagent.task.domain;

/** 与 async_task.task_type 的 CHECK 约束一致。 */
public enum TaskType {
    /** 文档入库：解析 → 切分 → Embedding → 写入 doc_chunk。biz_id 是 document_id。 */
    INGEST_DOCUMENT,
    /** 删除文档文件，然后删除文档记录。biz_id 是 document_id，payload 中带 storagePath。 */
    DELETE_FILE
}
