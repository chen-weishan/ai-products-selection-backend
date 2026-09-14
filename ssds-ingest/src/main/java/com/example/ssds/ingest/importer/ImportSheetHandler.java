package com.example.ssds.ingest.importer;

import java.util.List;

/** 串流讀取匯入檔案時的 callback；rowNumber 是包含標題列的原始檔案列號。 */
public interface ImportSheetHandler {
    void onHeaders(List<String> headers);

    void onRow(int rowNumber, List<String> values);
}
