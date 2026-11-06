package com.example.kbagent.chat.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数字核对：回答中的每个数字都应该能在本轮的查询结果中找到出处，找不到的标为“未核实”。
 * <p>
 * M4 用 qwen-plus 实测时，模型在没有调用任何工具的情况下直接给出了一个业务数字（华北上月异常事件“127 起”，
 * 真实是 56 起）。提示词约束是概率性的，所以和引用合法性（§4.5 层次 A）一样，在服务端做一次确定性的后处理。
 * 它只回答“这个数字有没有出处”，不判断计算是否正确。
 * <p>
 * 出处包括本轮工具的参数、返回结果和摘要，以及用户的问题。比较时容忍常见的写法差异：千分位、四舍五入到 0～2 位小数、
 * 换算成“万”“亿”、中文数字（资料原文的“六小时”）、PDF 换行把 450 拆成“4\n50”。
 * 占比（带 % 的数字）如果等于出处中某两个数字相除的结果（按回答中的小数位数四舍五入），也算有出处：
 * 分析类回答里很常见，每次都提示会让用户习惯性忽略，真正编造的数字反而不显眼。增长率等其他推算仍会被标出。
 * 时长换算同理：出处中以分钟为单位的数值（字段名以 Minutes 结尾，或文字“N 分钟”），回答写成“6.3 小时”
 * “6 小时 18 分钟”时，按回答的精度换算回分钟比对。只和分钟数值比，不会因为碰巧接近某个订单数而放过编造的时长。
 */
public final class NumberGrounding {

    /**
     * @param unverified 找不到出处的数字（按回答中出现的写法，去重，最多 10 个）
     * @param noEvidence 本轮没有任何查询结果可以作为出处（没有调用工具）
     */
    public record Result(List<String> unverified, boolean noEvidence) {

        public boolean verified() {
            return unverified.isEmpty();
        }
    }

    private static final int MAX_REPORTED = 10;

    /** 一个数字：带千分位的，或者普通的整数、小数。 */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\d.])(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?");
    private static final Pattern CHINESE_NUMBER = Pattern.compile("[零〇一二两三四五六七八九十百千万]+");
    private static final Pattern DIGITS_SPLIT_BY_WHITESPACE = Pattern.compile("(?<=\\d)\\s+(?=\\d)");

    /** 回答中不当作数据的数字：引用标记、日期、月份、年份、序号、列表编号、标识符。 */
    private static final List<Pattern> NOT_DATA = List.of(
            CitationParser.MARK,
            Pattern.compile("\\d{4}\\s*[-/年]\\s*\\d{1,2}(?:\\s*[-/月]\\s*\\d{1,2}\\s*日?)?"),
            Pattern.compile("\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日"),
            Pattern.compile("\\d{1,2}\\s*月份?"),
            Pattern.compile("\\d{4}\\s*年"),
            Pattern.compile("第\\s*\\d+"),
            Pattern.compile("(?m)^\\s*\\d{1,2}[.、)）]\\s*"),
            Pattern.compile("[A-Za-z_]+\\d+"));

    /** 出处中以分钟为单位的数值：JSON 字段名以 Minutes 结尾，或文字“N 分钟”。 */
    private static final Pattern MINUTES_FIELD = Pattern.compile("\"\\w*[Mm]inutes\"\\s*:\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern MINUTES_TEXT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*分钟");
    /** 回答中的时长：“6.3 小时”“6 小时 18 分钟”。 */
    private static final Pattern HOURS = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*个?小时(?:\\s*(\\d+(?:\\.\\d+)?)\\s*分钟?)?");
    private static final BigDecimal SIXTY = new BigDecimal("60");

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");
    /** 参与占比推算的出处数字上限，限制两两相除的计算量。 */
    private static final int MAX_RATIO_OPERANDS = 400;
    private static final BigDecimal HUNDRED_MILLION = new BigDecimal("100000000");

    private NumberGrounding() {
    }

    /**
     * @param evidence 本轮查询结果的文本（工具参数、返回的 JSON、摘要）；为空表示没有调用工具
     */
    public static Result check(String answer, String question, Collection<String> evidence) {
        NavigableSet<BigDecimal> known = new TreeSet<>();
        NavigableSet<BigDecimal> operands = new TreeSet<>();
        for (String text : evidence) {
            collect(text, known, operands);
        }
        collect(question, known, operands);

        List<BigDecimal> minutes = new ArrayList<>();
        for (String text : evidence) {
            collectMinutes(text, minutes);
        }

        List<String> unverified = new ArrayList<>();
        NavigableSet<BigDecimal> reported = new TreeSet<>();
        String text = withoutConvertedDurations(withoutNonData(answer == null ? "" : answer), minutes);
        Matcher m = NUMBER.matcher(text);
        while (m.find() && unverified.size() < MAX_REPORTED) {
            BigDecimal value = parse(m.group());
            if (value == null || known.contains(value)
                    || isPercentage(text, m.end()) && derivableShare(value, operands)) {
                continue;
            }
            if (reported.add(value)) {
                unverified.add(m.group());
            }
        }
        return new Result(List.copyOf(unverified), evidence.isEmpty());
    }

    private static boolean isPercentage(String text, int end) {
        int i = end;
        while (i < text.length() && text.charAt(i) == ' ') {
            i++;
        }
        return i < text.length() && (text.charAt(i) == '%' || text.charAt(i) == '％');
    }

    /** 占比 = a / b × 100（0 ≤ a ≤ b），按回答中的小数位数四舍五入后相等。 */
    private static boolean derivableShare(BigDecimal value, NavigableSet<BigDecimal> operands) {
        if (value.signum() < 0 || value.compareTo(HUNDRED) > 0) {
            return false;
        }
        int scale = Math.max(0, value.scale());
        for (BigDecimal whole : operands) {
            if (whole.signum() <= 0) {
                continue;
            }
            for (BigDecimal part : operands.headSet(whole, true)) {
                if (part.signum() >= 0
                        && part.multiply(HUNDRED).divide(whole, scale, RoundingMode.HALF_UP).compareTo(value) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void collectMinutes(String text, List<BigDecimal> minutes) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (Pattern pattern : List.of(MINUTES_FIELD, MINUTES_TEXT)) {
            Matcher m = pattern.matcher(text);
            while (m.find()) {
                minutes.add(new BigDecimal(m.group(1)));
            }
        }
    }

    /**
     * 去掉能由出处中的分钟数换算得到的时长。容差是回答精度的一半：
     * “6.3 小时”是 ±3 分钟，“6 小时 18 分钟”是 ±0.5 分钟，“约 6 小时”是 ±30 分钟。
     */
    private static String withoutConvertedDurations(String text, List<BigDecimal> minutes) {
        if (minutes.isEmpty()) {
            return text;
        }
        Matcher m = HOURS.matcher(text);
        StringBuilder result = new StringBuilder();
        while (m.find()) {
            BigDecimal hours = new BigDecimal(m.group(1));
            BigDecimal total = hours.multiply(SIXTY);
            BigDecimal tolerance;
            if (m.group(2) != null) {
                BigDecimal extra = new BigDecimal(m.group(2));
                total = total.add(extra);
                tolerance = BigDecimal.ONE.movePointLeft(Math.max(0, extra.scale())).divide(BigDecimal.TWO);
            } else {
                tolerance = SIXTY.movePointLeft(Math.max(0, hours.scale())).divide(BigDecimal.TWO);
            }
            BigDecimal target = total;
            boolean converted = minutes.stream()
                    .anyMatch(v -> v.subtract(target).abs().compareTo(tolerance) <= 0);
            m.appendReplacement(result, converted ? " " : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(result);
        return result.toString();
    }

    /** 追加在回答末尾的提示；全部有出处时返回 null。 */
    public static String note(Result result) {
        if (result == null || result.verified()) {
            return null;
        }
        String numbers = String.join("、", result.unverified());
        return result.noEvidence()
                ? "\n\n> 数字核对：本轮没有调用任何查询工具，回答中的数字没有查询依据，请不要采用：" + numbers + "。"
                : "\n\n> 数字核对：以下数字没有出现在本轮的查询结果中，可能是模型计算或推测得出的，请以查询结果为准：" + numbers + "。";
    }

    private static String withoutNonData(String text) {
        String result = text;
        for (Pattern pattern : NOT_DATA) {
            result = pattern.matcher(result).replaceAll(" ");
        }
        return result;
    }

    /** 把文本中的数字（含各种等价写法）加入出处集合；原始数值另外记下，用于推算占比。 */
    private static void collect(String text, NavigableSet<BigDecimal> known, NavigableSet<BigDecimal> operands) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (String variant : List.of(text, DIGITS_SPLIT_BY_WHITESPACE.matcher(text).replaceAll(""))) {
            Matcher m = NUMBER.matcher(variant);
            while (m.find()) {
                BigDecimal value = parse(m.group());
                addWithEquivalents(value, known);
                if (value != null && operands.size() < MAX_RATIO_OPERANDS) {
                    operands.add(value);
                }
            }
        }
        Matcher chinese = CHINESE_NUMBER.matcher(text);
        while (chinese.find()) {
            addWithEquivalents(BigDecimal.valueOf(parseChinese(chinese.group())), known);
        }
    }

    private static void addWithEquivalents(BigDecimal value, NavigableSet<BigDecimal> known) {
        if (value == null) {
            return;
        }
        known.add(value);
        for (int scale = 0; scale <= 2; scale++) {
            known.add(value.setScale(scale, RoundingMode.HALF_UP));
            if (value.abs().compareTo(new BigDecimal("1000")) >= 0) {
                known.add(value.divide(TEN_THOUSAND, scale, RoundingMode.HALF_UP));
            }
            if (value.abs().compareTo(new BigDecimal("10000000")) >= 0) {
                known.add(value.divide(HUNDRED_MILLION, scale, RoundingMode.HALF_UP));
            }
        }
    }

    private static BigDecimal parse(String token) {
        try {
            return new BigDecimal(token.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 中文数字转整数：六 → 6，十二 → 12，一百二十 → 120，三千五百 → 3500，两万 → 20000。 */
    static long parseChinese(String text) {
        long total = 0;
        long section = 0;
        long digit = 0;
        for (char ch : text.toCharArray()) {
            switch (ch) {
                case '零', '〇' -> digit = 0;
                case '一' -> digit = 1;
                case '二', '两' -> digit = 2;
                case '三' -> digit = 3;
                case '四' -> digit = 4;
                case '五' -> digit = 5;
                case '六' -> digit = 6;
                case '七' -> digit = 7;
                case '八' -> digit = 8;
                case '九' -> digit = 9;
                case '十' -> {
                    section += (digit == 0 ? 1 : digit) * 10;
                    digit = 0;
                }
                case '百' -> {
                    section += digit * 100;
                    digit = 0;
                }
                case '千' -> {
                    section += digit * 1000;
                    digit = 0;
                }
                case '万' -> {
                    total += (section + digit) * 10_000;
                    section = 0;
                    digit = 0;
                }
                default -> {
                    // 正则保证不会出现其他字符
                }
            }
        }
        return total + section + digit;
    }
}
