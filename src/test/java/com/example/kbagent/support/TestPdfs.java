package com.example.kbagent.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** 在测试中生成 PDF。使用标准字体，只支持 ASCII 文本。 */
public final class TestPdfs {

    private static final int CHARS_PER_LINE = 90;

    private TestPdfs() {
    }

    /** 每个参数是一页的文本。 */
    public static byte[] withPages(String... pageTexts) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 10);
                    content.setLeading(13);
                    content.newLineAtOffset(40, 750);
                    for (String line : wrap(text)) {
                        content.showText(line);
                        content.newLine();
                    }
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 一页带有页码标记的英文文本，约 chars 个字符。 */
    public static String pageText(int pageNumber, int chars) {
        String sentence = "This is page " + pageNumber + " of the travel policy. ";
        return sentence.repeat(Math.max(1, chars / sentence.length()));
    }

    private static List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        for (int start = 0; start < text.length(); start += CHARS_PER_LINE) {
            lines.add(text.substring(start, Math.min(text.length(), start + CHARS_PER_LINE)));
        }
        return lines;
    }
}
