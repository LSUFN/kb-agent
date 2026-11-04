package com.example.kbagent.document.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.document.domain.Document;
import com.example.kbagent.document.domain.DocumentStatus;
import com.example.kbagent.document.ingest.FileDeletionHandler;
import com.example.kbagent.document.repository.DocChunkRepository;
import com.example.kbagent.document.repository.DocumentRepository;
import com.example.kbagent.document.service.DocumentViews.ChunkView;
import com.example.kbagent.document.service.DocumentViews.DocumentView;
import com.example.kbagent.document.service.DocumentViews.TaskView;
import com.example.kbagent.document.service.DocumentViews.UploadResult;
import com.example.kbagent.knowledge.domain.KbAccess;
import com.example.kbagent.knowledge.service.KbPermissionService;
import com.example.kbagent.storage.StorageService;
import com.example.kbagent.storage.StorageService.FileTooLargeException;
import com.example.kbagent.storage.StorageService.TempFile;
import com.example.kbagent.task.domain.TaskType;
import com.example.kbagent.task.service.TaskService;
import com.example.kbagent.user.domain.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

/**
 * 文档的上传、删除、重新处理和查询（§4.1、§6.2）。
 * <p>
 * 文件系统不能参与数据库事务，所以上传采用“先写文件、再提交数据库，失败时补偿删除”的顺序，
 * 进程在两步之间崩溃留下的孤儿文件由 StorageReconciler 清理。数据库记录是权威的。
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** 与 spring.servlet.multipart.max-file-size 一致，写临时文件时再检查一次。 */
    static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_FILENAME_LENGTH = 255;

    private final DocumentRepository documents;
    private final DocChunkRepository chunks;
    private final KbPermissionService permissions;
    private final StorageService storage;
    private final TaskService tasks;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public DocumentService(DocumentRepository documents, DocChunkRepository chunks, KbPermissionService permissions,
                           StorageService storage, TaskService tasks, TransactionTemplate transactionTemplate,
                           ObjectMapper objectMapper) {
        this.documents = documents;
        this.chunks = chunks;
        this.permissions = permissions;
        this.storage = storage;
        this.tasks = tasks;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 上传（§4.1）：① 流式写临时文件并计算 SHA-256 → ② 同一知识库已有相同内容则直接返回 → ③ 原子移动到正式目录
     * → ④ 短事务插入文档和入库任务 → ⑤ 提交后唤醒任务扫描。④ 失败时删除 ③ 移动的文件。
     * 这个方法本身不开事务：文件 I/O 期间不占用数据库连接。
     */
    public UploadResult upload(Actor actor, long kbId, MultipartFile file) {
        permissions.require(actor, kbId, KbAccess.EDIT);
        String filename = sanitizeFilename(file.getOriginalFilename());
        TempFile temp = writeTemp(file);
        try {
            if (!startsWith(temp.head(), PDF_MAGIC)) {
                throw new BusinessException(ErrorCode.INVALID_FILE);
            }
            Optional<Document> duplicate = documents.findActiveDuplicate(kbId, temp.sha256());
            if (duplicate.isPresent()) {
                return new UploadResult(DocumentView.of(duplicate.get()), false);
            }
            String storagePath = StorageService.DOCS_AREA + "/" + kbId + "/" + UUID.randomUUID() + ".pdf";
            storage.promote(temp, storagePath);
            try {
                Document created = transactionTemplate.execute(status -> {
                    Document doc = documents.saveAndFlush(Document.create(kbId, filename, temp.sha256(), storagePath,
                            temp.size(), actor.userId()));
                    tasks.submit(TaskType.INGEST_DOCUMENT, doc.id(), null);
                    return doc;
                });
                return new UploadResult(DocumentView.of(created), true);
            } catch (DataIntegrityViolationException e) {
                deleteQuietly(storagePath);
                // 两个请求并发上传同一个文件：部分唯一索引保证只有一个成功，另一个返回已有文档
                return documents.findActiveDuplicate(kbId, temp.sha256())
                        .map(existing -> new UploadResult(DocumentView.of(existing), false))
                        .orElseThrow(() -> e);
            } catch (RuntimeException e) {
                deleteQuietly(storagePath);
                throw e;
            }
        } finally {
            storage.deleteTemp(temp);
        }
    }

    /** @param status 为 null 时不过滤；@param page 从 1 开始 */
    @Transactional(readOnly = true)
    public PageResponse<DocumentView> list(Actor actor, long kbId, DocumentStatus status, int page, int size) {
        permissions.require(actor, kbId, KbAccess.READ);
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id"));
        Page<Document> result = status == null
                ? documents.findByKbId(kbId, pageable)
                : documents.findByKbIdAndStatus(kbId, status, pageable);
        return PageResponse.of(result, DocumentView::of);
    }

    @Transactional(readOnly = true)
    public DocumentView get(Actor actor, long documentId) {
        Document doc = loadWithAccess(actor, documentId, KbAccess.READ, () -> documents.findById(documentId));
        TaskView task = tasks.findLatest(TaskType.INGEST_DOCUMENT, documentId).map(TaskView::of).orElse(null);
        return DocumentView.of(doc, task);
    }

    /** 只有 READY 或 FAILED 的文档可以重新处理；正在处理或删除中返回 409。 */
    public DocumentView reprocess(Actor actor, long documentId) {
        return transactionTemplate.execute(status -> {
            Document doc = loadWithAccess(actor, documentId, KbAccess.EDIT,
                    () -> documents.findByIdForUpdate(documentId));
            if (doc.status() != DocumentStatus.READY && doc.status() != DocumentStatus.FAILED) {
                throw new BusinessException(ErrorCode.CONFLICT, "文档正在处理或删除中，不能重新处理");
            }
            doc.requeue();
            try {
                tasks.submit(TaskType.INGEST_DOCUMENT, doc.id(), null);
            } catch (DuplicateKeyException e) {
                throw new BusinessException(ErrorCode.CONFLICT, "文档已有正在执行的处理任务");
            }
            return DocumentView.of(doc);
        });
    }

    /**
     * 删除（§4.1）：短事务中锁定文档、改为 DELETING、删除全部 chunk（检索立即看不到）、取消入库任务、
     * 提交 DELETE_FILE 任务；文件和文档记录由 FileDeletionHandler 异步删除。重复删除是幂等的。
     */
    public DocumentView delete(Actor actor, long documentId) {
        return transactionTemplate.execute(status -> {
            Document doc = loadWithAccess(actor, documentId, KbAccess.EDIT,
                    () -> documents.findByIdForUpdate(documentId));
            if (doc.status() == DocumentStatus.DELETING) {
                return DocumentView.of(doc);
            }
            doc.markDeleting();
            chunks.deleteByDocument(doc.id());
            tasks.cancelActive(TaskType.INGEST_DOCUMENT, doc.id());
            tasks.submit(TaskType.DELETE_FILE, doc.id(), FileDeletionHandler.payload(objectMapper, doc.storagePath()));
            return DocumentView.of(doc);
        });
    }

    /** 分页查看切分结果，用于调试和验收。@param page 从 1 开始 */
    @Transactional(readOnly = true)
    public PageResponse<ChunkView> chunks(Actor actor, long documentId, int page, int size) {
        loadWithAccess(actor, documentId, KbAccess.READ, () -> documents.findById(documentId));
        List<ChunkView> items = chunks.page(documentId, (page - 1) * size, size).stream()
                .map(ChunkView::of)
                .toList();
        return new PageResponse<>(items, page, size, chunks.countByDocument(documentId));
    }

    /** 文档不存在、或用户对其所在知识库没有任何访问权限时，都返回“文档不存在”。 */
    private Document loadWithAccess(Actor actor, long documentId, KbAccess required,
                                    Supplier<Optional<Document>> loader) {
        Document doc = loader.get().orElseThrow(() -> BusinessException.notFound("文档"));
        try {
            permissions.require(actor, doc.kbId(), required);
        } catch (BusinessException e) {
            if (e.errorCode() == ErrorCode.NOT_FOUND) {
                throw BusinessException.notFound("文档");
            }
            throw e;
        }
        return doc;
    }

    private TempFile writeTemp(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return storage.writeTemp(in, MAX_UPLOAD_BYTES);
        } catch (FileTooLargeException e) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteQuietly(String storagePath) {
        try {
            storage.delete(storagePath);
        } catch (RuntimeException e) {
            log.warn("compensating delete of {} failed; the reconciler will remove it", storagePath, e);
        }
    }

    /** 去掉浏览器可能带上的路径和控制字符，限制长度；空文件名用默认名。 */
    static String sanitizeFilename(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            return "未命名.pdf";
        }
        return name.length() <= MAX_FILENAME_LENGTH ? name : name.substring(0, MAX_FILENAME_LENGTH);
    }

    private static boolean startsWith(byte[] head, byte[] prefix) {
        return head.length >= prefix.length && Arrays.equals(head, 0, prefix.length, prefix, 0, prefix.length);
    }
}
