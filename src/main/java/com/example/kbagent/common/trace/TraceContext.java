package com.example.kbagent.common.trace;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/**
 * traceId 的读写入口。traceId 存放在 SLF4J MDC 中，日志 pattern 通过 %X{traceId} 输出。
 */
public final class TraceContext {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    /** 只接受 8-64 位字母、数字、连字符，防止日志注入和超长头部。 */
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    private TraceContext() {
    }

    public static String currentTraceId() {
        return MDC.get(MDC_KEY);
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static boolean isValid(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches();
    }
}
