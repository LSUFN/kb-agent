package com.example.kbagent.document.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.SocketTimeoutException;
import java.util.List;

import com.example.kbagent.support.FakeEmbeddingModel;
import com.example.kbagent.task.service.TaskExceptions.NonRetryableTaskException;
import com.example.kbagent.task.service.TaskExceptions.RetryableTaskException;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class EmbeddingClientTest {

    private final FakeEmbeddingModel model = new FakeEmbeddingModel(4);
    private final EmbeddingClient client = new EmbeddingClient(model, new EmbeddingProperties(4, 10, "fake"));

    @Test
    void returnsOneVectorPerText() {
        assertThat(client.embed(List.of("a", "b", "c"))).hasSize(3).allSatisfy(v -> assertThat(v).hasSize(4));
    }

    @Test
    void networkFailureIsRetryable() {
        model.failNextCalls(1);

        assertThatThrownBy(() -> client.embed(List.of("a"))).isInstanceOf(RetryableTaskException.class);
        assertThat(client.embed(List.of("a"))).hasSize(1);
    }

    @Test
    void dimensionMismatchIsNotRetryable() {
        EmbeddingClient misconfigured = new EmbeddingClient(model, new EmbeddingProperties(1024, 10, "fake"));

        assertThatThrownBy(() -> misconfigured.embed(List.of("a")))
                .isInstanceOfSatisfying(NonRetryableTaskException.class,
                        e -> assertThat(e.errorCode()).isEqualTo("EMBEDDING_DIMENSION_MISMATCH"));
    }

    @Test
    void rejectedRequestIsNotRetryableAndHidesUpstreamDetails() {
        EmbeddingModel rejecting = mock(EmbeddingModel.class);
        when(rejecting.embed(anyList())).thenThrow(BadRequestException.builder()
                .headers(Headers.builder().build()).build());

        assertThatThrownBy(() -> new EmbeddingClient(rejecting, new EmbeddingProperties(4, 10, "fake"))
                .embed(List.of("a")))
                .isInstanceOfSatisfying(NonRetryableTaskException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo("EMBEDDING_REJECTED");
                    assertThat(e.getMessage()).doesNotContain("400");
                });
    }

    @Test
    void classifiesUpstreamErrorsByStatusAndType() {
        Headers headers = Headers.builder().build();
        assertThat(EmbeddingClient.isTransient(InternalServerException.builder().statusCode(503).headers(headers).build()))
                .isTrue();
        assertThat(EmbeddingClient.isTransient(RateLimitException.builder().headers(headers).build())).isTrue();
        assertThat(EmbeddingClient.isTransient(BadRequestException.builder().headers(headers).build())).isFalse();
        assertThat(EmbeddingClient.isTransient(new OpenAIIoException("connection reset"))).isTrue();
        assertThat(EmbeddingClient.isTransient(new IllegalStateException(new SocketTimeoutException()))).isTrue();
        assertThat(EmbeddingClient.isTransient(new IllegalArgumentException("bad input"))).isFalse();
    }
}
