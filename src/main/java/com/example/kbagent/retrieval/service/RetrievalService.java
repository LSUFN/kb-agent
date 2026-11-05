package com.example.kbagent.retrieval.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.kbagent.common.error.BusinessException;
import com.example.kbagent.common.error.ErrorCode;
import com.example.kbagent.common.error.UpstreamErrors;
import com.example.kbagent.knowledge.domain.KbAccess;
import com.example.kbagent.knowledge.service.KbPermissionService;
import com.example.kbagent.observability.LlmCallRecorder;
import com.example.kbagent.retrieval.RetrievalProperties;
import com.example.kbagent.retrieval.repository.ChunkVectorRepository;
import com.example.kbagent.retrieval.repository.ChunkVectorRepository.ChunkHit;
import com.example.kbagent.user.domain.Actor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * 检索：把问题向量化，在当前用户有权访问的知识库中取最相近的片段（§4.7）。
 * 调用外部服务，不能开事务（ADR-05，ArchUnit 约束）；数据库查询在 ChunkVectorRepository 的短事务中完成。
 */
@Service
@EnableConfigurationProperties(RetrievalProperties.class)
public class RetrievalService {

    /** 检索到的片段。index 从 1 开始，就是回答中引用时使用的编号。 */
    public record Candidate(int index, long chunkId, long documentId, String filename, int pageNumber,
                            int chunkIndex, String content, double score) {

        static Candidate of(int index, ChunkHit hit) {
            return new Candidate(index, hit.chunkId(), hit.documentId(), hit.filename(), hit.pageNumber(),
                    hit.chunkIndex(), hit.content(), hit.score());
        }
    }

    /** 写调用日志用的上下文。 */
    public record CallContext(String traceId, Long conversationId) {
    }

    private final EmbeddingModel embeddingModel;
    private final ChunkVectorRepository chunks;
    private final KbPermissionService permissions;
    private final LlmCallRecorder recorder;
    private final RetrievalProperties properties;
    private final String embeddingModelName;

    public RetrievalService(EmbeddingModel embeddingModel, ChunkVectorRepository chunks,
                            KbPermissionService permissions, LlmCallRecorder recorder,
                            RetrievalProperties properties,
                            @Value("${app.embedding.model:unknown}") String embeddingModelName) {
        this.embeddingModel = embeddingModel;
        this.chunks = chunks;
        this.permissions = permissions;
        this.recorder = recorder;
        this.properties = properties;
        this.embeddingModelName = embeddingModelName;
    }

    /**
     * 用于问答：只返回相似度不低于阈值的片段，一个都没有时调用方应直接拒答。
     *
     * @param kbId 为 null 时检索当前用户有权访问的全部知识库
     */
    public List<Candidate> retrieve(Actor actor, Long kbId, String query, CallContext context) {
        return aboveThreshold(search(actor, kbId, query, properties.topK(), context));
    }

    /**
     * 用于 Agent 工具：在指定的一组知识库中检索，阈值规则与 {@link #retrieve} 相同。
     * 再按当前用户的权限过滤一遍，调用方传入的范围不会扩大用户的权限。
     */
    public List<Candidate> retrieveIn(Actor actor, Collection<Long> kbIds, String query, CallContext context) {
        Set<Long> accessible = new HashSet<>(permissions.accessibleKbIds(actor));
        List<Long> allowed = kbIds.stream().filter(accessible::contains).distinct().toList();
        return aboveThreshold(searchIn(actor, allowed, query, properties.topK(), context));
    }

    /** 用于检索调试：返回前 topK 个片段和相似度，不应用阈值。index 是排名。 */
    public List<Candidate> search(Actor actor, Long kbId, String query, int topK, CallContext context) {
        List<Long> kbIds = kbId != null
                ? List.of(permissions.require(actor, kbId, KbAccess.READ).kb().id())
                : permissions.accessibleKbIds(actor);
        return searchIn(actor, kbIds, query, topK, context);
    }

    private List<Candidate> searchIn(Actor actor, List<Long> kbIds, String query, int topK, CallContext context) {
        if (kbIds.isEmpty()) {
            return List.of();
        }
        List<ChunkHit> hits = chunks.search(embed(actor, query, context), kbIds, topK, properties.efSearch());
        List<Candidate> candidates = new ArrayList<>(hits.size());
        for (ChunkHit hit : hits) {
            candidates.add(Candidate.of(candidates.size() + 1, hit));
        }
        return candidates;
    }

    private List<Candidate> aboveThreshold(List<Candidate> ranked) {
        List<Candidate> candidates = new ArrayList<>();
        for (Candidate hit : ranked) {
            if (hit.score() >= properties.minScore()) {
                candidates.add(new Candidate(candidates.size() + 1, hit.chunkId(), hit.documentId(), hit.filename(),
                        hit.pageNumber(), hit.chunkIndex(), hit.content(), hit.score()));
            }
        }
        return candidates;
    }

    public int defaultTopK() {
        return properties.topK();
    }

    public double minScore() {
        return properties.minScore();
    }

    private float[] embed(Actor actor, String query, CallContext context) {
        long start = System.nanoTime();
        LlmCallRecorder.Entry entry = LlmCallRecorder.embedding()
                .traceId(context.traceId())
                .userId(actor.userId())
                .conversationId(context.conversationId())
                .model(embeddingModelName)
                .input(query);
        try {
            float[] vector = embeddingModel.embed(query);
            recorder.record(entry.latencyMs(elapsedMillis(start)));
            return vector;
        } catch (RuntimeException e) {
            ErrorCode code = UpstreamErrors.classify(e);
            recorder.record(entry.latencyMs(elapsedMillis(start)).failed(code.name(), e.getMessage()));
            throw new BusinessException(code);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
