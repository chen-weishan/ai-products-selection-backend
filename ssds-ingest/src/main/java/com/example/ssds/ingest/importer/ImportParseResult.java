package com.example.ssds.ingest.importer;

import java.util.List;

/** 上傳解析結果；previewRows 固定最多 20 列，totalRows 為完整資料列數。 */
public record ImportParseResult(
        List<String> headers,
        List<ImportColumnSuggestion> suggestions,
        List<List<String>> previewRows,
        int totalRows
) {
    public ImportParseResult {
        headers = List.copyOf(headers);
        suggestions = List.copyOf(suggestions);
        previewRows = previewRows.stream().map(List::copyOf).toList();
    }
}
