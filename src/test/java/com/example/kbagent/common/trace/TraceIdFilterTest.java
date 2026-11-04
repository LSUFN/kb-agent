package com.example.kbagent.common.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    /** 用一个 Servlet 捕获请求处理过程中 MDC 里的 traceId。 */
    private static MockFilterChain capturingChain(AtomicReference<String> seen) {
        return new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                seen.set(MDC.get(TraceContext.MDC_KEY));
            }
        });
    }

    @Test
    void generatesTraceIdWhenHeaderAbsent() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/system/ping");
        var response = new MockHttpServletResponse();
        var seen = new AtomicReference<String>();

        filter.doFilter(request, response, capturingChain(seen));

        String header = response.getHeader(TraceContext.HEADER);
        assertThat(header).isNotBlank().matches("[0-9a-f]{32}");
        assertThat(seen.get()).isEqualTo(header);
    }

    @Test
    void reusesValidInboundTraceId() throws Exception {
        var request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(TraceContext.HEADER, "frontend-trace-0001");
        var response = new MockHttpServletResponse();
        var seen = new AtomicReference<String>();

        filter.doFilter(request, response, capturingChain(seen));

        assertThat(response.getHeader(TraceContext.HEADER)).isEqualTo("frontend-trace-0001");
        assertThat(seen.get()).isEqualTo("frontend-trace-0001");
    }

    @Test
    void replacesInvalidInboundTraceIdToPreventLogInjection() throws Exception {
        var request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(TraceContext.HEADER, "abc\nFAKE LOG LINE");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, capturingChain(new AtomicReference<>()));

        assertThat(response.getHeader(TraceContext.HEADER))
                .doesNotContain("\n")
                .matches("[0-9a-f]{32}");
    }

    @Test
    void clearsMdcAfterRequest() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                capturingChain(new AtomicReference<>()));

        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }

    @Test
    void validationRejectsTooShortAndTooLongValues() {
        assertThat(TraceContext.isValid("short")).isFalse();
        assertThat(TraceContext.isValid("a".repeat(65))).isFalse();
        assertThat(TraceContext.isValid("a".repeat(64))).isTrue();
        assertThat(TraceContext.isValid(null)).isFalse();
    }
}
