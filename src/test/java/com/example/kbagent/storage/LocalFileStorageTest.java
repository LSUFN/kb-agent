package com.example.kbagent.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import com.example.kbagent.storage.StorageService.FileTooLargeException;
import com.example.kbagent.storage.StorageService.TempFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageTest {

    @TempDir
    Path root;

    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFileStorage(new StorageProperties(root, Duration.ofHours(1), Duration.ofHours(1)));
    }

    private TempFile write(String content) {
        return storage.writeTemp(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), 1024);
    }

    @Test
    void writeTempComputesHashSizeAndHead() throws Exception {
        byte[] content = "%PDF-1.7 hello world".getBytes(StandardCharsets.US_ASCII);

        TempFile temp = storage.writeTemp(new ByteArrayInputStream(content), 1024);

        assertThat(temp.size()).isEqualTo(content.length);
        assertThat(temp.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        assertThat(new String(temp.head(), StandardCharsets.US_ASCII)).isEqualTo("%PDF-1.7");
        assertThat(temp.relativePath()).startsWith("tmp/").endsWith(".part");
        assertThat(root.resolve(temp.relativePath())).hasBinaryContent(content);
    }

    @Test
    void headIsShorterForTinyFiles() {
        assertThat(write("%PD").head()).hasSize(3);
    }

    @Test
    void tooLargeContentIsRejectedAndNothingIsLeftBehind() {
        assertThatThrownBy(() -> storage.writeTemp(new ByteArrayInputStream(new byte[2048]), 1024))
                .isInstanceOf(FileTooLargeException.class);
        assertThat(root.resolve(StorageService.TMP_AREA)).isEmptyDirectory();
    }

    @Test
    void promoteMovesTheFileAndDeleteIsIdempotent() throws Exception {
        TempFile temp = write("%PDF-content");

        storage.promote(temp, "docs/7/doc.pdf");
        storage.deleteTemp(temp);

        assertThat(storage.exists("docs/7/doc.pdf")).isTrue();
        assertThat(root.resolve(temp.relativePath())).doesNotExist();
        try (InputStream in = storage.open("docs/7/doc.pdf")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("%PDF-content");
        }
        storage.delete("docs/7/doc.pdf");
        storage.delete("docs/7/doc.pdf");
        assertThat(storage.exists("docs/7/doc.pdf")).isFalse();
    }

    @Test
    void pathsEscapingTheRootAreRejected() {
        assertThatThrownBy(() -> storage.delete("../outside.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open("docs/../../outside.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.exists("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsOnlyFilesOlderThanTheCutoffWithForwardSlashes() throws Exception {
        storage.promote(write("old"), "docs/1/old.pdf");
        storage.promote(write("new"), "docs/1/new.pdf");
        Files.setLastModifiedTime(root.resolve("docs/1/old.pdf"),
                FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        assertThat(storage.listOlderThan(StorageService.DOCS_AREA, Instant.now().minus(Duration.ofHours(1))))
                .containsExactly("docs/1/old.pdf");
    }
}
