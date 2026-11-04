package com.example.kbagent.document.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/** §7：文档状态机的非法状态转换。 */
class DocumentStatusTest {

    private static Document newDocument() {
        return Document.create(1L, "a.pdf", "a".repeat(64), "docs/1/a.pdf", 10, 1L);
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING, READY", "PENDING, PENDING",
            "READY, PROCESSING", "READY, FAILED", "READY, READY",
            "FAILED, PROCESSING", "FAILED, READY",
            "PROCESSING, PENDING"})
    void illegalTransitionsAreRejected(DocumentStatus from, DocumentStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(DocumentStatus.class)
    void deletingIsTerminal(DocumentStatus target) {
        assertThat(DocumentStatus.DELETING.canTransitionTo(target)).isFalse();
    }

    @Test
    void happyPathAndReprocessing() {
        Document doc = newDocument();

        doc.startProcessing();
        doc.markReady(3, 12, "text-embedding-v3");
        assertThat(doc.status()).isEqualTo(DocumentStatus.READY);
        assertThat(doc.chunkCount()).isEqualTo(12);

        doc.requeue();
        doc.startProcessing();
        doc.markFailed("CORRUPT_PDF", "PDF 文件损坏");
        assertThat(doc.errorCode()).isEqualTo("CORRUPT_PDF");

        doc.requeue();
        assertThat(doc.status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(doc.errorCode()).isNull();
        assertThat(doc.errorMessage()).isNull();
    }

    @Test
    void processingCanBeRestartedAfterAWorkerDied() {
        Document doc = newDocument();
        doc.startProcessing();

        doc.startProcessing();

        assertThat(doc.status()).isEqualTo(DocumentStatus.PROCESSING);
    }

    @Test
    void deletedDocumentCannotBeProcessed() {
        Document doc = newDocument();
        doc.markDeleting();

        assertThatThrownBy(doc::startProcessing).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(doc::markDeleting).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void longErrorMessagesAreTruncatedToColumnLength() {
        Document doc = newDocument();

        doc.markFailed("X", "错".repeat(2000));

        assertThat(doc.errorMessage()).hasSize(1000);
    }
}
