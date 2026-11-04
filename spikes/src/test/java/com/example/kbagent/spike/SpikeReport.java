package com.example.kbagent.spike;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/**
 * 把每项验证的观察结果追加写入 target/spike-report.md，便于回填到架构文档。
 * 观察结果如实记录；“是否符合预期”由人阅读报告后判断。
 */
final class SpikeReport {

    private static final Path FILE = Path.of("target", "spike-report.md");

    private SpikeReport() {
    }

    static synchronized void record(String spikeId, String item, Object observation) {
        String line = "| " + LocalDateTime.now().withNano(0) + " | " + spikeId + " | " + item + " | "
                + String.valueOf(observation).replace("|", "\\|").replace("\n", " ") + " |\n";
        try {
            Files.createDirectories(FILE.getParent());
            if (Files.notExists(FILE)) {
                Files.writeString(FILE, "| 时间 | Spike | 项目 | 观察结果 |\n|---|---|---|---|\n",
                        StandardCharsets.UTF_8);
            }
            Files.writeString(FILE, line, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println("[SPIKE " + spikeId + "] " + item + " => " + observation);
    }
}
