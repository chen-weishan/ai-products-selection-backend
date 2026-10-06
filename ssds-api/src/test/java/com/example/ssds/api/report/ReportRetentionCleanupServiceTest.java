package com.example.ssds.api.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssds.api.report.config.ReportProperties;
import com.example.ssds.api.report.service.ReportFileStorage;
import com.example.ssds.api.report.service.ReportRetentionCleanupService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportRetentionCleanupServiceTest {
    @TempDir Path temp;

    @Test
    void deletesOnlyExpiredReportFiles() throws Exception {
        Path year = Files.createDirectories(temp.resolve("2026"));
        Path expiredPdf = Files.writeString(year.resolve("expired.pdf"), "old");
        Path freshXlsx = Files.writeString(year.resolve("fresh.xlsx"), "new");
        Path unrelated = Files.writeString(year.resolve("notes.txt"), "keep");
        FileTime old = FileTime.from(Instant.now().minus(91, ChronoUnit.DAYS));
        Files.setLastModifiedTime(expiredPdf, old);
        Files.setLastModifiedTime(unrelated, old);

        ReportProperties properties = new ReportProperties(temp.toString(), 5000, 90, "");
        new ReportRetentionCleanupService(new ReportFileStorage(properties), properties)
                .cleanExpiredFiles();

        assertFalse(Files.exists(expiredPdf));
        assertTrue(Files.exists(freshXlsx));
        assertTrue(Files.exists(unrelated));
    }
}
