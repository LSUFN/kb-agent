package com.example.kbagent.document.service;

import java.util.HashSet;
import java.util.Set;

import com.example.kbagent.document.domain.DocumentStatus;
import com.example.kbagent.document.repository.DocumentRepository;
import com.example.kbagent.storage.StoredFileReferences;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 告诉对账任务哪些文件被文档引用（文档模块实现 storage 模块定义的端口）。 */
@Component
class DocumentFileReferences implements StoredFileReferences {

    private final DocumentRepository documents;

    DocumentFileReferences(DocumentRepository documents) {
        this.documents = documents;
    }

    /** 任何状态的文档都算引用：DELETING 文档的文件由删除任务负责，对账不能抢先删除。 */
    @Override
    @Transactional(readOnly = true)
    public Set<String> referencedPaths() {
        return new HashSet<>(documents.findAllStoragePaths());
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> requiredPaths() {
        return new HashSet<>(documents.findStoragePathsByStatus(DocumentStatus.READY));
    }
}
