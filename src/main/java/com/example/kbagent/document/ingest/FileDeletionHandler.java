package com.example.kbagent.document.ingest;

import com.example.kbagent.storage.StorageService;
import com.example.kbagent.task.domain.ClaimedTask;
import com.example.kbagent.task.domain.TaskType;
import com.example.kbagent.task.service.TaskContext;
import com.example.kbagent.task.service.TaskExceptions.NonRetryableTaskException;
import com.example.kbagent.task.service.TaskHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 删除文档的第二步（§4.1）：删除文件（文件不存在也算成功），然后删除文档记录。失败按任务机制重试。
 */
@Component
public class FileDeletionHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(FileDeletionHandler.class);

    private final StorageService storage;
    private final DocumentTaskTransactions transactions;
    private final ObjectMapper objectMapper;

    public FileDeletionHandler(StorageService storage, DocumentTaskTransactions transactions,
                               ObjectMapper objectMapper) {
        this.storage = storage;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    /** DELETE_FILE 任务的 payload。 */
    public static String payload(ObjectMapper objectMapper, String storagePath) {
        return objectMapper.writeValueAsString(new Payload(storagePath));
    }

    record Payload(String storagePath) {
    }

    @Override
    public TaskType type() {
        return TaskType.DELETE_FILE;
    }

    @Override
    public void execute(ClaimedTask task, TaskContext context) {
        String storagePath = task.payload() == null ? null
                : objectMapper.readValue(task.payload(), Payload.class).storagePath();
        if (storagePath == null || storagePath.isBlank()) {
            throw new NonRetryableTaskException("INVALID_PAYLOAD", "删除任务缺少文件路径");
        }
        storage.delete(storagePath);
        transactions.finishDeletion(context, task.bizId());
    }

    @Override
    public void onGiveUp(ClaimedTask task, String errorCode, String userMessage) {
        // 文档记录保留在 DELETING 状态并继续引用该文件，对账不会误删；需要管理员处理
        log.error("deleting the file of document {} failed permanently: {}", task.bizId(), errorCode);
    }

    @Override
    public void onManualRetry(long bizId) {
        transactions.checkDeletionPending(bizId);
    }
}
