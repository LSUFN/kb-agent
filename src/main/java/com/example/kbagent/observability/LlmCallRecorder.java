package com.example.kbagent.observability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 写入 llm_call_log（§4.6）。在写入时脱敏：
 * <ul>
 *   <li>System Prompt、检索片段和完整回答一律不记录，只记录长度</li>
 *   <li>用户问题按 {@link LlmLogProperties#content()} 处理，并且只保存 SHA-256</li>
 *   <li>错误信息先脱敏再截断到 1000 字（先截断可能切开手机号等，导致漏掉脱敏）</li>
 * </ul>
 * 写日志失败只记一条告警，不影响业务。
 */
@Component
@EnableConfigurationProperties(LlmLogProperties.class)
public class LlmCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(LlmCallRecorder.class);
    private static final int PREVIEW_LENGTH = 200;
    private static final int ERROR_LENGTH = 1000;

    /** 一次模型调用的记录。用 {@link #chat()} 或 {@link #embedding()} 创建。 */
    public static final class Entry {

        private final String callType;
        private String traceId;
        private Long userId;
        private Long conversationId;
        private String chatMode;
        private String model = "unknown";
        private boolean streamed;
        private Integer promptTokens;
        private Integer completionTokens;
        private boolean usageEstimated;
        private String input;
        private Integer outputChars;
        private Integer firstTokenMs;
        private long latencyMs;
        private boolean success = true;
        private String errorCode;
        private String errorMessage;

        private Entry(String callType) {
            this.callType = callType;
        }

        public Entry traceId(String value) {
            this.traceId = value;
            return this;
        }

        public Entry userId(Long value) {
            this.userId = value;
            return this;
        }

        public Entry conversationId(Long value) {
            this.conversationId = value;
            return this;
        }

        public Entry chatMode(String value) {
            this.chatMode = value;
            return this;
        }

        public Entry model(String value) {
            if (value != null && !value.isBlank()) {
                this.model = value;
            }
            return this;
        }

        public Entry streamed(boolean value) {
            this.streamed = value;
            return this;
        }

        public Entry usage(Integer prompt, Integer completion, boolean estimated) {
            this.promptTokens = prompt;
            this.completionTokens = completion;
            this.usageEstimated = estimated;
            return this;
        }

        /** 原始的用户输入；写入前才会脱敏和计算摘要。 */
        public Entry input(String value) {
            this.input = value;
            return this;
        }

        public Entry outputChars(Integer value) {
            this.outputChars = value;
            return this;
        }

        public Entry firstTokenMs(Integer value) {
            this.firstTokenMs = value;
            return this;
        }

        public Entry latencyMs(long value) {
            this.latencyMs = value;
            return this;
        }

        public Entry failed(String code, String message) {
            this.success = false;
            this.errorCode = code;
            this.errorMessage = message;
            return this;
        }
    }

    public static Entry chat() {
        return new Entry("CHAT");
    }

    public static Entry embedding() {
        return new Entry("EMBEDDING");
    }

    private final JdbcClient jdbc;
    private final LlmLogProperties properties;

    public LlmCallRecorder(JdbcClient jdbc, LlmLogProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public void record(Entry e) {
        try {
            jdbc.sql("""
                            INSERT INTO llm_call_log(trace_id, user_id, conversation_id, call_type, chat_mode, model, streamed,
                                prompt_tokens, completion_tokens, usage_estimated, input_preview_masked, input_sha256,
                                output_chars, first_token_ms, latency_ms, success, error_code, error_message_masked)
                            VALUES (:traceId, :userId, :conversationId, :callType, :chatMode, :model, :streamed,
                                :promptTokens, :completionTokens, :usageEstimated, :preview, :sha256,
                                :outputChars, :firstTokenMs, :latencyMs, :success, :errorCode, :errorMessage)""")
                    .param("traceId", e.traceId)
                    .param("userId", e.userId)
                    .param("conversationId", e.conversationId)
                    .param("callType", e.callType)
                    .param("chatMode", e.chatMode)
                    .param("model", e.model)
                    .param("streamed", e.streamed)
                    .param("promptTokens", e.promptTokens)
                    .param("completionTokens", e.completionTokens)
                    .param("usageEstimated", e.usageEstimated)
                    .param("preview", preview(e.input))
                    .param("sha256", e.input == null ? null : sha256(e.input))
                    .param("outputChars", e.outputChars)
                    .param("firstTokenMs", e.firstTokenMs)
                    .param("latencyMs", (int) Math.min(Integer.MAX_VALUE, e.latencyMs))
                    .param("success", e.success)
                    .param("errorCode", e.errorCode)
                    .param("errorMessage", truncate(SensitiveDataMasker.mask(e.errorMessage), ERROR_LENGTH))
                    .update();
        } catch (RuntimeException ex) {
            log.warn("failed to write llm_call_log (traceId={})", e.traceId, ex);
        }
    }

    private String preview(String input) {
        if (input == null || properties.content() == LlmLogProperties.ContentPolicy.NONE) {
            return null;
        }
        return truncate(SensitiveDataMasker.mask(input), PREVIEW_LENGTH);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
