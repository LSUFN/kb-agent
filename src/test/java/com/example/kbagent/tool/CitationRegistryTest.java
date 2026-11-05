package com.example.kbagent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.example.kbagent.retrieval.service.RetrievalService.Candidate;
import org.junit.jupiter.api.Test;

/** §4.4.4：一轮对话中多次检索的引用编号全局分配。 */
class CitationRegistryTest {

    private static Candidate chunk(int rank, long chunkId) {
        return new Candidate(rank, chunkId, 9L, "制度.pdf", (int) chunkId, 0, "片段 " + chunkId, 0.8);
    }

    @Test
    void secondSearchContinuesNumberingAndReusesKnownChunks() {
        CitationRegistry registry = new CitationRegistry();

        List<Candidate> first = registry.register(List.of(chunk(1, 100), chunk(2, 101), chunk(3, 102)));
        List<Candidate> second = registry.register(List.of(chunk(1, 103), chunk(2, 101)));

        assertThat(first).extracting(Candidate::index).containsExactly(1, 2, 3);
        assertThat(second).extracting(Candidate::index).containsExactly(4, 2);
        assertThat(second.getFirst().chunkId()).isEqualTo(103);
        assertThat(registry.all()).extracting(Candidate::chunkId).containsExactly(100L, 101L, 102L, 103L);
    }

    @Test
    void emptySearchRegistersNothing() {
        CitationRegistry registry = new CitationRegistry();

        assertThat(registry.register(List.of())).isEmpty();
        assertThat(registry.all()).isEmpty();
    }
}
