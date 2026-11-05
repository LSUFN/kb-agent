package com.example.kbagent.observability;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注工具参数 record 中的敏感字段（§4.6）：写审计日志和推送 tool 事件之前，按这里的策略处理。
 * 没有标注的字符串字段默认也按 MASK 处理；枚举、日期、数字原样记录。
 */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.RUNTIME)
public @interface Sensitive {

    Strategy value() default Strategy.MASK;

    enum Strategy {
        /** 按 SensitiveDataMasker 的规则脱敏后记录。 */
        MASK,
        /** 只记录 SHA-256 的前 12 位，可以判断两次调用的参数是否相同。 */
        HASH,
        /** 不记录。 */
        DROP
    }
}
