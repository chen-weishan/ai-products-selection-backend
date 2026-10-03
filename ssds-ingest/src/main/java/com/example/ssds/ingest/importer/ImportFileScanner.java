package com.example.ssds.ingest.importer;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 提供預覽驗證階段重掃完整檔案，逐列處理而不累積在記憶體。 */
@Service
public class ImportFileScanner {

    private final List<ImportTabularReader> readers;
    private final int hardMaxRows;

    public ImportFileScanner(
            List<ImportTabularReader> readers,
            @Value("${ssds.import.hard-max-rows:200000}") int hardMaxRows
    ) {
        this.readers = List.copyOf(readers);
        this.hardMaxRows = hardMaxRows;
    }

    public void scan(Path path, String originalFilename, ImportSheetHandler handler) {
        ImportFileFormat format = ImportFileFormat.fromFilename(originalFilename);
        readers.stream()
                .filter(candidate -> candidate.supports(format))
                .findFirst()
                .orElseThrow(() -> new ImportFileParseException("找不到對應的檔案解析器"))
                .scan(path, hardMaxRows, handler);
    }

    /** 只解析標題列，供 confirm 驗證 mapping，不為了幾個欄名重掃完整檔案。 */
    public List<String> readHeaders(Path path, String originalFilename) {
        AtomicReference<List<String>> captured = new AtomicReference<>();
        try {
            scan(path, originalFilename, new ImportSheetHandler() {
                @Override
                public void onHeaders(List<String> headers) {
                    captured.set(List.copyOf(headers));
                    throw ImportHeaderCapturedException.INSTANCE;
                }

                @Override
                public void onRow(int rowNumber, List<String> values) {}
            });
        } catch (ImportHeaderCapturedException ignored) {
            // 以內部控制流程在標題列後立刻停止 parser。
        }
        if (captured.get() == null) {
            throw new ImportFileParseException("檔案必須包含標題列");
        }
        return captured.get();
    }

    static final class ImportHeaderCapturedException extends RuntimeException {
        static final ImportHeaderCapturedException INSTANCE = new ImportHeaderCapturedException();

        private ImportHeaderCapturedException() {
            super(null, null, false, false);
        }
    }
}
