package com.example.kbagent.storage;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param root              存储根目录（APP_STORAGE_ROOT）
 * @param orphanGracePeriod 对账时只清理早于这个时间的文件，避免误删正在上传的文件（§4.1：1 小时）
 * @param reconcileInterval 对账间隔
 */
@ConfigurationProperties("app.storage")
public record StorageProperties(
        @DefaultValue("./data/storage") Path root,
        @DefaultValue("1h") Duration orphanGracePeriod,
        @DefaultValue("1h") Duration reconcileInterval) {
}
