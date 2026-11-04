package com.example.kbagent.document.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.example.kbagent.task.service.TaskExceptions.NonRetryableTaskException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * 用 PDFBox 按页提取文本。直接使用 PDFTextStripper 而不是 Spring AI 的 PDF 读取器：
 * 我们有自己的 chunk 模型（ADR-02），只需要“页码 + 文本”，依赖更少、行为可控。
 */
@Component
public class PdfTextExtractor {

    /** PDF 损坏或加密属于不可重试的错误。 */
    public List<PageText> extract(InputStream pdf) {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(pdf))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            int pageCount = document.getNumberOfPages();
            List<PageText> pages = new ArrayList<>(pageCount);
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                pages.add(new PageText(page, stripper.getText(document)));
            }
            return pages;
        } catch (InvalidPasswordException e) {
            throw new NonRetryableTaskException("PDF_ENCRYPTED", "PDF 已加密，无法解析", e);
        } catch (IOException e) {
            throw new NonRetryableTaskException("CORRUPT_PDF", "PDF 文件损坏或格式不受支持", e);
        }
    }
}
