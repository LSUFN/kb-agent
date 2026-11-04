package com.example.kbagent.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.openai.errors.OpenAIIoException;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 集成测试用的确定性 Embedding（§7 的 DeterministicEmbeddingModel）：向量由文本哈希生成，
 * 只用于验证 SQL、流程和一致性，不用于评估语义检索质量。
 * <p>
 * 可以注入故障：让接下来的若干次调用失败、让每次调用阻塞一段时间、或者卡住直到测试放行。
 * 也可以为指定文本预设向量（{@link #alias}），精确控制检索结果。
 * 所有集成测试共用一个实例，每个测试结束后调用 {@link #reset()}。
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    private final int dimensions;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger failuresRemaining = new AtomicInteger();
    private final AtomicBoolean calledInsideTransaction = new AtomicBoolean();
    private final Semaphore callsStarted = new Semaphore(0);
    private volatile Duration delayPerCall = Duration.ZERO;
    private volatile CountDownLatch gate;
    private final Map<String, List<String>> aliases = new ConcurrentHashMap<>();

    public FakeEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    /** 接下来的 n 次调用抛出网络错误（可重试）。 */
    public void failNextCalls(int n) {
        failuresRemaining.set(n);
    }

    public void delayEachCall(Duration delay) {
        this.delayPerCall = delay;
    }

    /** 之后的调用都会卡住，直到 {@link #openGate()}。 */
    public void closeGate() {
        gate = new CountDownLatch(1);
    }

    public void openGate() {
        CountDownLatch current = gate;
        gate = null;
        if (current != null) {
            current.countDown();
        }
    }

    public boolean awaitCallStarted(Duration timeout) throws InterruptedException {
        return callsStarted.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public int calls() {
        return calls.get();
    }

    /**
     * 让 text 的向量等于 targets 各自向量之和（归一化）。哈希生成的高维向量近似正交，
     * 所以 text 与每个 target 的余弦相似度约为 1/√n（1 个 target 时为 1，3 个时约 0.58），与其他文本约为 0。
     */
    public void alias(String text, String... targets) {
        aliases.put(text, List.of(targets));
    }

    /** 是否有调用发生在数据库事务中（ADR-05 不允许）。 */
    public boolean calledInsideTransaction() {
        return calledInsideTransaction.get();
    }

    public void reset() {
        openGate();
        calls.set(0);
        failuresRemaining.set(0);
        calledInsideTransaction.set(false);
        callsStarted.drainPermits();
        delayPerCall = Duration.ZERO;
        aliases.clear();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        calls.incrementAndGet();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            calledInsideTransaction.set(true);
        }
        callsStarted.release();
        waitIfRequested();
        if (failuresRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new OpenAIIoException("503 Service Unavailable (simulated)");
        }
        List<String> texts = request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(new Embedding(vectorFor(texts.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vectorFor(document.getText());
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private void waitIfRequested() {
        try {
            if (!delayPerCall.isZero()) {
                Thread.sleep(delayPerCall);
            }
            CountDownLatch current = gate;
            if (current != null && !current.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("FakeEmbeddingModel gate was never opened");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 由文本决定的单位向量；设置了 alias 时是各 target 向量之和（归一化）。 */
    public float[] vectorFor(String text) {
        List<String> targets = aliases.get(text);
        if (targets == null) {
            return hashVector(text);
        }
        float[] sum = new float[dimensions];
        for (String target : targets) {
            float[] v = hashVector(target);
            for (int i = 0; i < dimensions; i++) {
                sum[i] += v[i];
            }
        }
        return normalize(sum);
    }

    private float[] hashVector(String text) {
        Random random = new Random(text.hashCode());
        float[] vector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) random.nextGaussian();
        }
        return normalize(vector);
    }

    private static float[] normalize(float[] vector) {
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        float scale = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= scale;
        }
        return vector;
    }
}
