package com.example.ssds.api.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ssds.report")
public record ReportProperties(
        String storagePath,
        int asyncRowThreshold,
        int retentionDays,
        String pdfFontPath
) {
    public ReportProperties {
        if (storagePath == null || storagePath.isBlank()) {
            storagePath = "./.local/reports";
        }
        if (asyncRowThreshold <= 0) {
            asyncRowThreshold = 5000;
        }
        if (retentionDays <= 0) {
            retentionDays = 90;
        }
        pdfFontPath = pdfFontPath == null ? "" : pdfFontPath.trim();
    }
}
