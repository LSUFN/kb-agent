package com.example.kbagent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

/** 应用日志的两个脱敏转换器：消息（%m）和异常堆栈（%wEx）。 */
class MaskingMessageConverterTest {

    private final LoggerContext context = new LoggerContext();
    private final Logger logger = context.getLogger("test");

    @Test
    void masksTheFormattedMessageIncludingArguments() {
        LoggingEvent event = new LoggingEvent("fqcn", logger, Level.INFO, "user {} called from {}", null,
                new Object[]{"13812345678", "zhang@example.com"});

        String converted = new MaskingMessageConverter().convert(event);

        assertThat(converted).isEqualTo("user 138****5678 called from z***@example.com");
    }

    @Test
    void masksExceptionMessagesAndCausesInStackTraces() {
        IllegalStateException error = new IllegalStateException("upstream rejected input: 手机 13812345678",
                new RuntimeException("Authorization: Bearer abcdefgh12345678 key=sk-abcdefghijklmnop1234"));
        LoggingEvent event = new LoggingEvent("fqcn", logger, Level.ERROR, "Aggregation Error", error, null);
        MaskingThrowableProxyConverter converter = new MaskingThrowableProxyConverter();
        converter.setContext(context);
        converter.start();

        String converted = converter.convert(event);

        assertThat(converted)
                .contains("IllegalStateException: upstream rejected input: 手机 138****5678")
                .contains("Caused by: java.lang.RuntimeException: Authorization: Bearer **** key=sk-****")
                .contains("at com.example.kbagent.observability.MaskingMessageConverterTest")
                .doesNotContain("13812345678", "abcdefgh12345678", "sk-abcdefghijklmnop1234");
    }
}
