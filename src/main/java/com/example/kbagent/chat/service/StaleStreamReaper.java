package com.example.kbagent.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 进程在流式输出过程中崩溃时，助手消息会一直停在 STREAMING。定期把超时的消息改为 INTERRUPTED（§4.3）。
 * 正常结束、失败和客户端断开都由 {@link KbQaService} 自己收尾，不依赖这里。
 */
@Component
@EnableConfigurationProperties(ChatProperties.class)
public class StaleStreamReaper {

    private static final Logger log = LoggerFactory.getLogger(StaleStreamReaper.class);

    private final ChatTransactions transactions;
    private final ChatProperties properties;

    public StaleStreamReaper(ChatTransactions transactions, ChatProperties properties) {
        this.transactions = transactions;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.chat.stale-scan-interval:1m}",
            initialDelayString = "${app.chat.stale-scan-interval:1m}")
    public void scheduledReap() {
        try {
            reap();
        } catch (RuntimeException e) {
            log.warn("stale stream scan failed", e);
        }
    }

    /** @return 被改为 INTERRUPTED 的消息条数 */
    public int reap() {
        int count = transactions.interruptStale(properties.staleStreamTimeout());
        if (count > 0) {
            log.warn("marked {} stale STREAMING message(s) as INTERRUPTED", count);
        }
        return count;
    }
}
