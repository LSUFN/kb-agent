package com.example.kbagent.observability;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感信息脱敏（§4.6）。在数据写入日志之前调用，而不是在读取时调用：即使日志表被整体导出，也不包含原文。
 * <p>
 * 规则按顺序执行：JWT → Bearer Token → API Key → 身份证号 → 银行卡号 → 手机号 → 邮箱。
 * 身份证号和银行卡号都要通过校验码检查，减少把订单号之类的长数字误判为敏感信息。
 * 纯静态方法：Logback 的转换器也要用它，那里无法注入 Spring Bean。
 */
public final class SensitiveDataMasker {

    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern API_KEY = Pattern.compile("(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{16,}");
    private static final Pattern ID_CARD = Pattern.compile("(?<![0-9A-Za-z])(\\d{6})(\\d{8})(\\d{3}[0-9Xx])(?![0-9A-Za-z])");
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<!\\d)\\d{13,19}(?!\\d)");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9])[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    private static final int[] ID_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final String ID_CHECK_CODES = "10X98765432";

    private SensitiveDataMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = JWT.matcher(text).replaceAll("eyJ****");
        result = BEARER.matcher(result).replaceAll("$1****");
        result = API_KEY.matcher(result).replaceAll("sk-****");
        result = maskIdCards(result);
        result = maskBankCards(result);
        result = PHONE.matcher(result).replaceAll("$1****$2");
        return EMAIL.matcher(result).replaceAll("$1***$2");
    }

    /** 保留前 6 位（地区）和后 4 位，出生日期等中间 8 位替换为 *。 */
    private static String maskIdCards(String text) {
        Matcher m = ID_CARD.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = isValidIdCard(m.group())
                    ? m.group(1) + "********" + m.group(3)
                    : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        return m.appendTail(sb).toString();
    }

    /** 只保留后 4 位。 */
    private static String maskBankCards(String text) {
        Matcher m = LONG_DIGITS.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group();
            String replacement = passesLuhn(digits)
                    ? "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4)
                    : digits;
            m.appendReplacement(sb, replacement);
        }
        return m.appendTail(sb).toString();
    }

    /** GB 11643：前 17 位加权求和后对 11 取余，对应校验码。 */
    static boolean isValidIdCard(String id) {
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (id.charAt(i) - '0') * ID_WEIGHTS[i];
        }
        return Character.toUpperCase(id.charAt(17)) == ID_CHECK_CODES.charAt(sum % 11);
    }

    static boolean passesLuhn(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
