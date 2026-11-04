package com.example.kbagent.document.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class SentenceAwareTextSplitterTest {

    private final SentenceAwareTextSplitter splitter = new SentenceAwareTextSplitter(500, 80);

    /** 长度为 n 的中文句子（以句号结尾）。 */
    private static String sentence(char filler, int n) {
        return String.valueOf(filler).repeat(n - 1) + "。";
    }

    private static List<String> contents(List<TextChunk> chunks) {
        return chunks.stream().map(TextChunk::content).toList();
    }

    @Test
    void shortPageBecomesSingleChunk() {
        List<TextChunk> chunks = splitter.split(List.of(new PageText(1, "  差旅报销需要在出差结束后 30 天内提交。  ")));

        assertThat(chunks).containsExactly(new TextChunk(0, 1, "差旅报销需要在出差结束后 30 天内提交。"));
    }

    @Test
    void noChunkExceedsTargetSizeEvenWithOverlap() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            text.append(sentence((char) ('甲' + i % 10), 37 + i % 50));
        }

        List<TextChunk> chunks = splitter.split(List.of(new PageText(1, text.toString())));

        assertThat(chunks).hasSizeGreaterThan(3);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.charCount()).isBetween(1, 500));
    }

    @Test
    void adjacentChunksOverlapWithTailOfPreviousChunk() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            text.append(sentence((char) ('一' + i % 9), 60));
        }

        List<String> chunks = contents(splitter.split(List.of(new PageText(1, text.toString()))));

        for (int i = 1; i < chunks.size(); i++) {
            String previous = chunks.get(i - 1);
            String current = chunks.get(i);
            int overlap = longestSuffixPrefix(previous, current);
            assertThat(overlap).as("chunk %d 的开头应当是 chunk %d 的结尾", i, i - 1).isBetween(1, 80);
        }
    }

    @Test
    void prefersParagraphBoundaryOverSentenceBoundary() {
        String para1 = sentence('甲', 150) + sentence('乙', 150);
        String para2 = sentence('丙', 150) + sentence('丁', 150);

        List<String> chunks = contents(splitter.split(List.of(new PageText(1, para1 + "\n\n" + para2))));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).isEqualTo(para1);
        assertThat(chunks.get(1)).endsWith(para2);
    }

    @Test
    void splitsChineseTextAtSentenceEndsKeepingClosingQuotes() {
        String s1 = "甲".repeat(200) + "。”";
        String s2 = "乙".repeat(200) + "！";
        String s3 = "丙".repeat(200) + "？";

        List<String> chunks = contents(splitter.split(List.of(new PageText(1, s1 + s2 + s3))));

        assertThat(chunks.get(0)).isEqualTo(s1 + s2);
        assertThat(chunks.get(1)).endsWith(s3);
    }

    @Test
    void overlongSentenceWithoutAnyDelimiterIsHardSplit() {
        String text = "长".repeat(1200);

        List<String> chunks = contents(splitter.split(List.of(new PageText(1, text))));

        assertThat(chunks).hasSizeGreaterThan(2).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(500));
        assertThat(String.join("", chunks).replace("长", "")).isEmpty();
    }

    @Test
    void chunksNeverCrossPagesAndKeepPageNumbers() {
        List<TextChunk> chunks = splitter.split(List.of(
                new PageText(1, "第一页。".repeat(200)),
                new PageText(2, "   "),
                new PageText(3, "第三页内容。")));

        assertThat(chunks).extracting(TextChunk::pageNumber).containsOnly(1, 3);
        assertThat(chunks).filteredOn(c -> c.pageNumber() == 1)
                .allSatisfy(c -> assertThat(c.content()).doesNotContain("第三页"));
        assertThat(chunks.getLast()).isEqualTo(new TextChunk(chunks.size() - 1, 3, "第三页内容。"));
        assertThat(chunks).extracting(TextChunk::index).containsExactlyElementsOf(
                java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
    }

    @Test
    void englishSentencesSplitOnlyAtPeriodsFollowedBySpace() {
        String sentenceText = "Version 2.0 of the policy applies to all travel. ";
        String text = sentenceText.repeat(25);

        List<String> chunks = contents(splitter.split(List.of(new PageText(1, text))));

        assertThat(chunks).allSatisfy(c -> assertThat(c).doesNotStartWith("0 of"));
        assertThat(chunks.getFirst()).startsWith("Version 2.0 of the policy");
    }

    @Test
    void normalizationRemovesNulCharactersAndCollapsesWhitespace() {
        assertThat(SentenceAwareTextSplitter.normalize("a\u0000b \t　 c\r\n\r\n\r\n\r\nd  \n  e"))
                .isEqualTo("ab c\n\nd\ne");
    }

    @Test
    void rejectsOverlapLargerThanHalfTheChunk() {
        assertThatThrownBy(() -> new SentenceAwareTextSplitter(100, 60)).isInstanceOf(IllegalArgumentException.class);
    }

    private static int longestSuffixPrefix(String previous, String current) {
        for (int length = Math.min(previous.length(), current.length()); length > 0; length--) {
            if (previous.endsWith(current.substring(0, length))) {
                return length;
            }
        }
        return 0;
    }
}
