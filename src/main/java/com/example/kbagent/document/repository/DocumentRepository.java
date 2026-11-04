package com.example.kbagent.document.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import com.example.kbagent.document.domain.Document;
import com.example.kbagent.document.domain.DocumentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * SELECT ... FOR UPDATE。删除流程和入库提交都先锁文档行、再改任务表，加锁顺序一致，避免死锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.id = :id")
    Optional<Document> findByIdForUpdate(long id);

    /** 同一知识库中内容相同、且不在删除中的文档（与部分唯一索引 uk_document_kb_hash 的条件一致）。 */
    @Query("select d from Document d where d.kbId = :kbId and d.contentHash = :contentHash and d.status <> :deleting")
    Optional<Document> findActiveDuplicate(long kbId, String contentHash, DocumentStatus deleting);

    default Optional<Document> findActiveDuplicate(long kbId, String contentHash) {
        return findActiveDuplicate(kbId, contentHash, DocumentStatus.DELETING);
    }

    Page<Document> findByKbId(long kbId, Pageable pageable);

    Page<Document> findByKbIdAndStatus(long kbId, DocumentStatus status, Pageable pageable);

    @Query("select d.storagePath from Document d")
    List<String> findAllStoragePaths();

    @Query("select d.storagePath from Document d where d.status = :status")
    List<String> findStoragePathsByStatus(DocumentStatus status);
}
