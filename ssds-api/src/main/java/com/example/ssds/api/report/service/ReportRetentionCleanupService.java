package com.example.ssds.api.report.service;

import com.example.ssds.api.report.config.ReportProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReportRetentionCleanupService {
    private static final Logger log = LoggerFactory.getLogger(ReportRetentionCleanupService.class);
    private final ReportFileStorage storage;
    private final int retentionDays;

    public ReportRetentionCleanupService(
            ReportFileStorage storage, ReportProperties properties) {
        this.storage = storage;
        this.retentionDays = properties.retentionDays();
    }

    @Scheduled(cron = "0 20 3 * * *", zone = "Asia/Taipei")
    public void cleanExpiredFiles() {
        Path root = storage.root();
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        try (var paths = Files.walk(root, 3)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isReportFile)
                    .filter(path -> olderThan(path, cutoff))
                    .forEach(this::delete);
        } catch (Exception exception) {
            log.warn("FR12 report retention cleanup failed", exception);
        }
    }

    private boolean isReportFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".pdf") || name.endsWith(".xlsx");
    }

    private boolean olderThan(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (Exception exception) {
            log.warn("Cannot inspect report file age: {}", path, exception);
            return false;
        }
    }

    private void delete(Path path) {
        try {
            Files.deleteIfExists(path);
            log.info("Deleted expired FR12 report: {}", path.getFileName());
        } catch (Exception exception) {
            log.warn("Cannot delete expired report: {}", path, exception);
        }
    }
}
