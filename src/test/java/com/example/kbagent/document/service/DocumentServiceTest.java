package com.example.kbagent.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DocumentServiceTest {

    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
            "'差旅制度.pdf',                 '差旅制度.pdf'",
            "'C:\\Users\\me\\Desktop\\a.pdf', 'a.pdf'",
            "'../../etc/passwd',            'passwd'",
            "'  报告.pdf  ',                 '报告.pdf'",
            "'',                            '未命名.pdf'",
            "-,                             '未命名.pdf'"})
    void filenamesAreStrippedOfPathsAndControlCharacters(String original, String expected) {
        assertThat(DocumentService.sanitizeFilename(original)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"300"})
    void longFilenamesAreTruncatedToColumnLength(int length) {
        assertThat(DocumentService.sanitizeFilename("文".repeat(length) + ".pdf")).hasSize(255);
    }
}
