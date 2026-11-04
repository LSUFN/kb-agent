package com.example.kbagent.observability;

import ch.qos.logback.classic.spi.IThrowableProxy;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/**
 * 异常堆栈也按 SensitiveDataMasker 的规则脱敏（§4.6）。在 logback-spring.xml 中覆盖 Spring Boot 默认格式里的 %wEx / %wex。
 * <p>
 * 只脱敏 %m 不够：第三方库会直接打印上游异常（例如 Spring AI 的 MessageAggregator 在流式调用失败时输出
 * “Aggregation Error”和完整堆栈），而上游的报错信息可能回显请求内容。KbQaIT 覆盖了这个场景。
 */
public class MaskingThrowableProxyConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return SensitiveDataMasker.mask(super.throwableProxyToString(tp));
    }
}
