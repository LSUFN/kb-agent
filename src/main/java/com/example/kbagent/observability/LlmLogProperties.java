package com.example.kbagent.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 调用日志配置（app.llm-log.*，§4.6）。
 *
 * @param content       是否记录用户问题的预览：NONE 不记录；MASKED_PREVIEW 脱敏后截取前 200 字（默认）
 * @param retentionDays 日志保留天数，过期后由每天一次的清理任务删除
 */
@ConfigurationProperties("app.llm-log")
public record LlmLogProperties(
        @DefaultValue("MASKED_PREVIEW") ContentPolicy content,
        @DefaultValue("30") int retentionDays) {

    public enum ContentPolicy {
        NONE,
        MASKED_PREVIEW
    }
}
