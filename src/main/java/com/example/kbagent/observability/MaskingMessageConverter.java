package com.example.kbagent.observability;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * 应用日志也按 SensitiveDataMasker 的规则脱敏（§4.6）。在 logback-spring.xml 中覆盖 %m / %msg / %message，
 * 所以 Spring Boot 默认的日志格式不需要修改。异常堆栈不经过这里，异常消息要在写入前自行脱敏。
 */
public class MaskingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SensitiveDataMasker.mask(super.convert(event));
    }
}
