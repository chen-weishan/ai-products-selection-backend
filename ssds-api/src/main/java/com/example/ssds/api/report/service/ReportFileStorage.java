package com.example.ssds.api.report.service;

import com.example.ssds.api.report.config.ReportProperties;
import com.example.ssds.core.domain.ReportFormat;
import com.example.ssds.core.domain.ReportType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ReportFileStorage {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");
    private final Path root;

    public ReportFileStorage(ReportProperties properties) {
        root = Path.of(properties.storagePath()).toAbsolutePath().normalize();
    }

    public Path target(
            long jobId, ReportType type, ReportFormat format, Map<String, Object> params) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        Path directory = root.resolve(Integer.toString(today.getYear())).normalize();
        ensureInsideRoot(directory);
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("無法建立報表目錄", exception);
        }
        String fileName = "%s-%s-%s-job-%d.%s".formatted(
                slug(type), today.toString().replace("-", ""), summary(params), jobId,
                format.name().toLowerCase());
        Path target = directory.resolve(fileName).normalize();
        ensureInsideRoot(target);
        return target;
    }

    public Path resolveStored(String storedPath) {
        Path path = Path.of(storedPath).toAbsolutePath().normalize();
        ensureInsideRoot(path);
        return path;
    }

    public Path root() {
        return root;
    }

    private String summary(Map<String, Object> params) {
        if (params.containsKey("period")) {
            return sanitize(params.get("period").toString())
                    + optional(params, "categoryId", "-cat-");
        }
        if (params.containsKey("from")) {
            return sanitize(params.get("from") + "-to-" + params.get("to"))
                    + optional(params, "categoryId", "-cat-")
                    + optional(params, "decisionMakerId", "-user-");
        }
        if (params.containsKey("fromQuarter") || params.containsKey("toQuarter")) {
            return sanitize(params.getOrDefault("fromQuarter", "all") + "-to-"
                    + params.getOrDefault("toQuarter", "all"))
                    + optional(params, "status", "-");
        }
        if (params.containsKey("status") || params.containsKey("categoryId")) {
            return "all" + optional(params, "status", "-")
                    + optional(params, "categoryId", "-cat-");
        }
        return "all";
    }

    private String optional(Map<String, Object> params, String key, String prefix) {
        Object value = params.get(key);
        return value == null ? "" : prefix + sanitize(value.toString());
    }

    private String sanitize(String value) {
        String result = value.toLowerCase().replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("-{2,}", "-").replaceAll("(^-|-$)", "");
        return result.isBlank() ? "all" : result;
    }

    private String slug(ReportType type) {
        return type.name().toLowerCase().replace('_', '-');
    }

    private void ensureInsideRoot(Path path) {
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("報表檔案路徑超出允許範圍");
        }
    }
}
