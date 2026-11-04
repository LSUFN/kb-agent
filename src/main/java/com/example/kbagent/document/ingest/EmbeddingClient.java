package com.example.kbagent.document.ingest;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import com.example.kbagent.task.service.TaskExceptions.NonRetryableTaskException;
import com.example.kbagent.task.service.TaskExceptions.RetryableTaskException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * 调用 EmbeddingModel，并把上游错误分成可重试和不可重试两类。
 * <p>
 * Spring AI 2.0 的 OpenAI 模块直接抛出 OpenAI SDK 的异常（Spike S2/S3），没有经过 spring-ai-retry 包装，
 * 所以这里按 HTTP 状态码和异常类型自己分类：408/409/429/5xx 和网络错误可重试，其余（400/401/403/404 等）
 * 重试也不会成功。不能在这个类上开事务（ADR-05，ArchUnit 约束）。
 */
@Component
public class EmbeddingClient {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingProperties properties;

    public EmbeddingClient(EmbeddingModel embeddingModel, EmbeddingProperties properties) {
        this.embeddingModel = embeddingModel;
        this.properties = properties;
    }

    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors;
        try {
            vectors = embeddingModel.embed(texts);
        } catch (RuntimeException e) {
            if (isTransient(e)) {
                throw new RetryableTaskException("Embedding service temporarily unavailable: " + e.getMessage(), e);
            }
            throw new NonRetryableTaskException("EMBEDDING_REJECTED", "向量化服务拒绝了请求，请联系管理员检查模型配置", e);
        }
        if (vectors.size() != texts.size()) {
            throw new RetryableTaskException("Embedding returned " + vectors.size() + " vectors for "
                    + texts.size() + " texts", null);
        }
        for (float[] vector : vectors) {
            if (vector.length != properties.dimensions()) {
                throw new NonRetryableTaskException("EMBEDDING_DIMENSION_MISMATCH",
                        "向量维度与配置不一致，请联系管理员检查模型配置");
            }
        }
        return vectors;
    }

    static boolean isTransient(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof OpenAIServiceException service) {
                int status = service.statusCode();
                return status == 408 || status == 409 || status == 429 || status >= 500;
            }
            if (t instanceof OpenAIIoException || t instanceof OpenAIRetryableException
                    || t instanceof IOException || t instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
