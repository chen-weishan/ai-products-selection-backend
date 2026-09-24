package com.example.ssds.ingest.importer;

import java.util.Locale;

/** FR-09 允許的上傳格式。 */
public enum ImportFileFormat {
    CSV,
    XLSX;

    public static ImportFileFormat fromFilename(String filename) {
        String normalized = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".csv")) {
            return CSV;
        }
        if (normalized.endsWith(".xlsx")) {
            return XLSX;
        }
        throw new ImportFileParseException("檔案格式只允許 CSV 或 XLSX");
    }
}
