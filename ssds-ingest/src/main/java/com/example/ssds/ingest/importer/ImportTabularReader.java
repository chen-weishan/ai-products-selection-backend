package com.example.ssds.ingest.importer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

interface ImportTabularReader {
    int PREVIEW_ROWS = 20;

    boolean supports(ImportFileFormat format);

    void scan(Path path, int hardMaxRows, ImportSheetHandler handler);

    default ImportTabularData read(Path path, int hardMaxRows) {
        List<String> headers = new ArrayList<>();
        List<List<String>> preview = new ArrayList<>();
        int[] totalRows = {0};
        scan(path, hardMaxRows, new ImportSheetHandler() {
            @Override
            public void onHeaders(List<String> values) {
                headers.addAll(values);
            }

            @Override
            public void onRow(int rowNumber, List<String> values) {
                totalRows[0]++;
                if (preview.size() < PREVIEW_ROWS) {
                    preview.add(values);
                }
            }
        });
        return new ImportTabularData(headers, preview, totalRows[0]);
    }
}
