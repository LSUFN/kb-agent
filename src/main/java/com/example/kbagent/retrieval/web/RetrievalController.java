package com.example.kbagent.retrieval.web;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.example.kbagent.common.api.ApiResponse;
import com.example.kbagent.common.trace.TraceContext;
import com.example.kbagent.retrieval.service.RetrievalService;
import com.example.kbagent.retrieval.service.RetrievalService.CallContext;
import com.example.kbagent.user.domain.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索调试（§6.2）：只检索、不生成回答，返回每个片段的相似度和是否达到问答使用的阈值，用于验收和调整阈值。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class RetrievalController {

    public record SearchRequest(@NotBlank @Size(max = 500) String query, @Min(1) @Max(20) Integer topK) {
    }

    public record SearchHit(long documentId, String filename, int pageNumber, int chunkIndex, double score,
                            boolean aboveThreshold, String content) {
    }

    public record SearchResult(double minScore, List<SearchHit> hits) {
    }

    private final RetrievalService retrieval;

    public RetrievalController(RetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    @PostMapping("/{kbId}/search")
    public ApiResponse<SearchResult> search(@AuthenticationPrincipal Actor actor, @PathVariable long kbId,
                                            @Valid @RequestBody SearchRequest request) {
        int topK = request.topK() == null ? retrieval.defaultTopK() : request.topK();
        double minScore = retrieval.minScore();
        List<SearchHit> hits = retrieval.search(actor, kbId, request.query(), topK,
                        new CallContext(TraceContext.currentTraceId(), null)).stream()
                .map(hit -> new SearchHit(hit.documentId(), hit.filename(), hit.pageNumber(), hit.chunkIndex(),
                        hit.score(), hit.score() >= minScore, hit.content()))
                .toList();
        return ApiResponse.ok(new SearchResult(minScore, hits));
    }
}
