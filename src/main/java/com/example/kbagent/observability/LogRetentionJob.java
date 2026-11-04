package com.example.kbagent.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每天删除超过保留期的调用日志（§4.6：默认 30 天）。 */
@Component
public class LogRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(LogRetentionJob.class);

    private final JdbcClient jdbc;
    private final LlmLogProperties properties;

    public LogRetentionJob(JdbcClient jdbc, LlmLogProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.llm-log.retention-cron:0 30 3 * * *}")
    public void purgeExpired() {
        int days = properties.retentionDays();
        int llm = jdbc.sql("DELETE FROM llm_call_log WHERE created_at < now() - :days * interval '1 day'")
                .param("days", days).update();
        int tool = jdbc.sql("DELETE FROM tool_call_log WHERE created_at < now() - :days * interval '1 day'")
                .param("days", days).update();
        if (llm + tool > 0) {
            log.info("purged {} llm_call_log and {} tool_call_log rows older than {} days", llm, tool, days);
        }
    }
}
