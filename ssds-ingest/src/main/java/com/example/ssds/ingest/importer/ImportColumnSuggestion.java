package com.example.ssds.ingest.importer;

/** 上傳後回傳給欄位對應畫面的單欄建議。 */
public record ImportColumnSuggestion(
        String sourceHeader,
        String systemField,
        ImportMappingStatus status,
        int confidence
) {}
