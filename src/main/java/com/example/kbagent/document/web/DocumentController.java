package com.example.kbagent.document.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.example.kbagent.common.api.ApiResponse;
import com.example.kbagent.common.api.PageResponse;
import com.example.kbagent.document.domain.DocumentStatus;
import com.example.kbagent.document.service.DocumentService;
import com.example.kbagent.document.service.DocumentViews.ChunkView;
import com.example.kbagent.document.service.DocumentViews.DocumentView;
import com.example.kbagent.document.service.DocumentViews.UploadResult;
import com.example.kbagent.user.domain.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档（§6.2）。上传、删除、重新处理都是异步的：返回 202，前端轮询文档详情查看进度。
 */
@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    /** 新文档返回 202；知识库中已有内容相同的文档时返回 200 和已有文档。 */
    @PostMapping(path = "/knowledge-bases/{kbId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentView>> upload(@AuthenticationPrincipal Actor actor,
                                                            @PathVariable long kbId,
                                                            @RequestPart("file") MultipartFile file) {
        UploadResult result = documents.upload(actor, kbId, file);
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(ApiResponse.ok(result.document()));
    }

    @GetMapping("/knowledge-bases/{kbId}/documents")
    public ApiResponse<PageResponse<DocumentView>> list(@AuthenticationPrincipal Actor actor,
                                                        @PathVariable long kbId,
                                                        @RequestParam(required = false) DocumentStatus status,
                                                        @RequestParam(defaultValue = "1") @Min(1) int page,
                                                        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(documents.list(actor, kbId, status, page, size));
    }

    @GetMapping("/documents/{docId}")
    public ApiResponse<DocumentView> get(@AuthenticationPrincipal Actor actor, @PathVariable long docId) {
        return ApiResponse.ok(documents.get(actor, docId));
    }

    @PostMapping("/documents/{docId}/reprocess")
    public ResponseEntity<ApiResponse<DocumentView>> reprocess(@AuthenticationPrincipal Actor actor,
                                                               @PathVariable long docId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(documents.reprocess(actor, docId)));
    }

    @DeleteMapping("/documents/{docId}")
    public ResponseEntity<ApiResponse<DocumentView>> delete(@AuthenticationPrincipal Actor actor,
                                                            @PathVariable long docId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(documents.delete(actor, docId)));
    }

    @GetMapping("/documents/{docId}/chunks")
    public ApiResponse<PageResponse<ChunkView>> chunks(@AuthenticationPrincipal Actor actor,
                                                       @PathVariable long docId,
                                                       @RequestParam(defaultValue = "1") @Min(1) int page,
                                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(documents.chunks(actor, docId, page, size));
    }
}
