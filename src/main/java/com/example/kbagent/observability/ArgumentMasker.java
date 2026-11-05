package com.example.kbagent.observability;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.TemporalAccessor;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按 {@link Sensitive} 把工具参数 record 转成可以记录的 Map（字段顺序与 record 一致，值为 null 的字段省略）。
 * 用于 tool_call_log.args_masked 和推送给前端的 tool 事件。
 */
public final class ArgumentMasker {

    private ArgumentMasker() {
    }

    public static Map<String, Object> mask(Record args) {
        Map<String, Object> masked = new LinkedHashMap<>();
        if (args == null) {
            return masked;
        }
        for (RecordComponent component : args.getClass().getRecordComponents()) {
            Object value = read(component, args);
            Sensitive sensitive = component.getAnnotation(Sensitive.class);
            if (value == null || (sensitive != null && sensitive.value() == Sensitive.Strategy.DROP)) {
                continue;
            }
            masked.put(component.getName(), maskValue(value, sensitive));
        }
        return masked;
    }

    private static Object maskValue(Object value, Sensitive sensitive) {
        if (sensitive != null && sensitive.value() == Sensitive.Strategy.HASH) {
            return "sha256:" + sha256(String.valueOf(value)).substring(0, 12);
        }
        return switch (value) {
            case Enum<?> e -> e.name();
            case Number n -> n;
            case Boolean b -> b;
            case TemporalAccessor t -> t.toString();
            default -> SensitiveDataMasker.mask(String.valueOf(value));
        };
    }

    private static Object read(RecordComponent component, Record args) {
        try {
            // 参数 record 可能声明在非 public 的类里，访问器本身是 public 的，但所在类型不可访问
            Method accessor = component.getAccessor();
            accessor.trySetAccessible();
            return accessor.invoke(args);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("cannot read " + component.getName(), e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
