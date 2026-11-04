package com.example.kbagent.document.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 面向中文的切分器（§4.7），纯函数。
 * <ul>
 *   <li>切分点优先级：空行（段落）→ 句末标点 {@code 。！？；!?;} 和英文句点 → 换行 → 定长硬切</li>
 *   <li>目标长度 chunkSize（默认 500 字），相邻 chunk 重叠 overlap（默认 80 字），任何 chunk 都不超过 chunkSize</li>
 *   <li>不跨页：每页单独切分，chunk 保留页码，编号在整个文档内连续</li>
 * </ul>
 * 做法：先按优先级递归切成不超过 {@code chunkSize - overlap} 的片段（保留分隔符），再贪心合并成 chunk；
 * 新 chunk 以上一个 chunk 的末尾作为重叠部分开头，所以“重叠 + 一个片段”一定不超过 chunkSize。
 */
public class SentenceAwareTextSplitter {

    private static final String SENTENCE_ENDS = "。！？；!?;";
    /** 紧跟在句末标点后面的闭合引号和括号，归入前一句。 */
    private static final String CLOSERS = "”’」』）)】》\"'";
    private static final Pattern HORIZONTAL_WHITESPACE = Pattern.compile("[ \\t\\x0B\\f\\u00A0\\u3000]+");
    private static final Pattern SPACES_AROUND_NEWLINE = Pattern.compile(" *\\n *");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private final int chunkSize;
    private final int overlap;
    private final int maxPieceLength;

    public SentenceAwareTextSplitter(int chunkSize, int overlap) {
        if (chunkSize <= 0 || overlap < 0 || overlap * 2 > chunkSize) {
            throw new IllegalArgumentException("chunkSize 必须为正数，overlap 不能超过 chunkSize 的一半");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.maxPieceLength = chunkSize - overlap;
    }

    public List<TextChunk> split(List<PageText> pages) {
        List<TextChunk> chunks = new ArrayList<>();
        for (PageText page : pages) {
            String text = normalize(page.text());
            if (text.isEmpty()) {
                continue;
            }
            for (String content : splitPage(text)) {
                chunks.add(new TextChunk(chunks.size(), page.pageNumber(), content));
            }
        }
        return chunks;
    }

    /**
     * 去掉 NUL（PostgreSQL 的 TEXT 不能包含）、统一换行符、合并连续空白、把三个以上的换行压成一个空行。
     */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String t = text.replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n');
        t = HORIZONTAL_WHITESPACE.matcher(t).replaceAll(" ");
        t = SPACES_AROUND_NEWLINE.matcher(t).replaceAll("\n");
        t = BLANK_LINES.matcher(t).replaceAll("\n\n");
        return t.strip();
    }

    private List<String> splitPage(String text) {
        List<String> pieces = new ArrayList<>();
        cut(text, 0, pieces);
        return merge(pieces);
    }

    /** 按优先级递归切分，直到每个片段不超过 maxPieceLength。片段保留各自末尾的分隔符。 */
    private void cut(String text, int level, List<String> out) {
        if (text.length() <= maxPieceLength) {
            out.add(text);
            return;
        }
        switch (level) {
            case 0 -> splitAfter(text, "\n\n").forEach(part -> cut(part, 1, out));
            case 1 -> splitAfterSentenceEnds(text).forEach(part -> cut(part, 2, out));
            case 2 -> splitAfter(text, "\n").forEach(part -> cut(part, 3, out));
            default -> out.addAll(fixedLength(text));
        }
    }

    private List<String> merge(List<String> pieces) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        // current 中是否有重叠部分以外的新内容；只有重叠部分时不单独输出
        boolean hasNewContent = false;
        for (String piece : pieces) {
            if (hasNewContent && current.length() + piece.length() > chunkSize) {
                String chunk = current.toString().strip();
                chunks.add(chunk);
                current = new StringBuilder(overlapTail(chunk));
                hasNewContent = false;
            }
            current.append(piece);
            hasNewContent = hasNewContent || !piece.isBlank();
        }
        String last = current.toString().strip();
        if (hasNewContent && !last.isEmpty()) {
            chunks.add(last);
        }
        return chunks;
    }

    /** 取上一个 chunk 末尾不超过 overlap 个字符；如果其中有句子边界，从边界之后开始，避免以半句话开头。 */
    private String overlapTail(String chunk) {
        if (overlap == 0) {
            return "";
        }
        int start = Math.max(0, chunk.length() - overlap);
        if (start > 0 && Character.isLowSurrogate(chunk.charAt(start))) {
            start++;
        }
        String tail = chunk.substring(start);
        for (int i = 0; i < tail.length() - 1; i++) {
            if (isSentenceEnd(tail, i) || tail.charAt(i) == '\n') {
                String fromBoundary = tail.substring(i + 1).stripLeading();
                return fromBoundary.isEmpty() ? tail : fromBoundary;
            }
        }
        return tail;
    }

    private static List<String> splitAfter(String text, String separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int index;
        while ((index = text.indexOf(separator, start)) >= 0) {
            int end = index + separator.length();
            parts.add(text.substring(start, end));
            start = end;
        }
        if (start < text.length()) {
            parts.add(text.substring(start));
        }
        return parts;
    }

    private static List<String> splitAfterSentenceEnds(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isSentenceEnd(text, i)) {
                int end = i + 1;
                while (end < text.length() && CLOSERS.indexOf(text.charAt(end)) >= 0) {
                    end++;
                }
                parts.add(text.substring(start, end));
                start = end;
                i = end - 1;
            }
        }
        if (start < text.length()) {
            parts.add(text.substring(start));
        }
        return parts;
    }

    /** 中文句末标点；英文句点只有后面跟着空白时才算句末，避免切开小数和缩写。 */
    private static boolean isSentenceEnd(String text, int i) {
        char c = text.charAt(i);
        if (SENTENCE_ENDS.indexOf(c) >= 0) {
            return true;
        }
        return c == '.' && i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1));
    }

    private List<String> fixedLength(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxPieceLength, text.length());
            if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            parts.add(text.substring(start, end));
            start = end;
        }
        return parts;
    }
}
