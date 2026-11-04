package com.example.kbagent.storage;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文件对账（§4.1）。系统只保证一个不变式：数据库记录是权威的，没有对应记录的文件就是垃圾。
 * <ul>
 *   <li>临时区中超过宽限期的文件：删除（上传中途失败或进程崩溃留下的）</li>
 *   <li>正式区中超过宽限期、且没有被任何文档引用的文件：删除（移动文件后、提交数据库前崩溃留下的）</li>
 *   <li>文档是 READY 但文件丢失：只记录告警和指标，不自动修改数据</li>
 * </ul>
 * 宽限期用来避免误删正在上传、尚未提交数据库的文件。
 */
@Component
public class StorageReconciler {

    private static final Logger log = LoggerFactory.getLogger(StorageReconciler.class);

    public record Report(int tempFilesDeleted, int orphanFilesDeleted, int missingFiles) {
    }

    private final StorageService storage;
    private final StoredFileReferences references;
    private final StorageProperties properties;
    private final Clock clock;
    private final AtomicInteger missingFiles = new AtomicInteger();

    public StorageReconciler(StorageService storage, StoredFileReferences references, StorageProperties properties,
                             Clock clock, MeterRegistry meterRegistry) {
        this.storage = storage;
        this.references = references;
        this.properties = properties;
        this.clock = clock;
        Gauge.builder("kbagent.storage.missing.files", missingFiles, AtomicInteger::get)
                .description("Number of files referenced by READY documents that are missing on disk")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.storage.reconcile-interval:1h}",
            initialDelayString = "${app.storage.reconcile-interval:1h}")
    public void scheduledReconcile() {
        try {
            reconcile();
        } catch (RuntimeException e) {
            log.error("storage reconciliation failed", e);
        }
    }

    public Report reconcile() {
        Instant cutoff = clock.instant().minus(properties.orphanGracePeriod());

        List<String> staleTempFiles = storage.listOlderThan(StorageService.TMP_AREA, cutoff);
        staleTempFiles.forEach(storage::delete);

        // 先列文件、再查引用：列出之后才提交的记录一定在引用集合里；而早于宽限期的文件不可能还在上传中
        List<String> oldFiles = storage.listOlderThan(StorageService.DOCS_AREA, cutoff);
        Set<String> referenced = references.referencedPaths();
        List<String> orphans = oldFiles.stream().filter(path -> !referenced.contains(path)).toList();
        orphans.forEach(storage::delete);

        List<String> missing = references.requiredPaths().stream().filter(path -> !storage.exists(path)).toList();
        missingFiles.set(missing.size());
        if (!missing.isEmpty()) {
            log.warn("{} file(s) referenced by READY documents are missing, e.g. {}", missing.size(),
                    missing.subList(0, Math.min(5, missing.size())));
        }

        Report report = new Report(staleTempFiles.size(), orphans.size(), missing.size());
        if (report.tempFilesDeleted() + report.orphanFilesDeleted() > 0) {
            log.info("storage reconciliation: {}", report);
        }
        return report;
    }
}
