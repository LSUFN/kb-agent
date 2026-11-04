package com.example.kbagent.chat.service;

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从回答中解析引用编号（§4.5 层次 A）。纯函数，结果是确定的：
 * <ul>
 *   <li>支持 {@code [1]}、{@code [1][2]}、{@code [1,2]}、{@code [1，2]}、{@code 【1】}、{@code ［1］}</li>
 *   <li>编号必须在 1..候选数之内，其余（0、负数、越界）记入 invalid，不会映射到任何文档</li>
 *   <li>重复编号去重；结果按编号升序</li>
 *   <li>代码块和行内代码里的 {@code [n]} 不解析（例如数组下标）</li>
 * </ul>
 */
public final class CitationParser {

    /** 一组引用标记：方括号（半角、全角、【】）内是一个或多个整数，用逗号、顿号或空白分隔。 */
    static final Pattern MARK = Pattern.compile(
            "[\\[【［]\\s*-?\\d{1,6}(?:\\s*[,，、\\s]\\s*-?\\d{1,6})*\\s*[\\]】］]");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+");
    /** 围栏代码块；没有闭合时（例如输出被中断）一直到结尾都算代码。 */
    private static final Pattern FENCED_CODE = Pattern.compile("(?s)(```|~~~).*?(?:\\1|\\z)");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]+`");

    /**
     * @param cited   合法的编号，升序
     * @param invalid 不合法的编号，升序
     */
    public record Result(List<Integer> cited, List<Integer> invalid) {
    }

    private CitationParser() {
    }

    public static Result parse(String answer, int candidateCount) {
        if (answer == null || answer.isEmpty()) {
            return new Result(List.of(), List.of());
        }
        String text = INLINE_CODE.matcher(FENCED_CODE.matcher(answer).replaceAll(" ")).replaceAll(" ");
        SortedSet<Integer> cited = new TreeSet<>();
        SortedSet<Integer> invalid = new TreeSet<>();
        Matcher mark = MARK.matcher(text);
        while (mark.find()) {
            Matcher number = NUMBER.matcher(mark.group());
            while (number.find()) {
                int index = Integer.parseInt(number.group());
                (index >= 1 && index <= candidateCount ? cited : invalid).add(index);
            }
        }
        return new Result(List.copyOf(cited), List.copyOf(invalid));
    }

    /** 去掉回答中的引用标记（代码块内的也会去掉，只用于构造历史消息）。 */
    public static String stripMarks(String answer) {
        return answer == null ? "" : MARK.matcher(answer).replaceAll("");
    }
}
