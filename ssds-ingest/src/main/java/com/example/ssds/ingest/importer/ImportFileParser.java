package com.example.ssds.ingest.importer;

import com.example.ssds.core.domain.ImportDataType;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** CSV／XLSX 的統一入口：格式選擇、列數硬上限、欄位猜測與前 20 列預覽。 */
@Service
public class ImportFileParser {

    private final List<ImportTabularReader> readers;
    private final ImportHeaderMapper headerMapper;
    private final int hardMaxRows;

    public ImportFileParser(
            List<ImportTabularReader> readers,
            ImportHeaderMapper headerMapper,
            @Value("${ssds.import.hard-max-rows:200000}") int hardMaxRows
    ) {
        this.readers = List.copyOf(readers);
        this.headerMapper = headerMapper;
        this.hardMaxRows = hardMaxRows;
    }

    public ImportParseResult parse(Path path, String originalFilename, ImportDataType dataType) {
        ImportFileFormat format = ImportFileFormat.fromFilename(originalFilename);
        ImportTabularReader reader = readers.stream()
                .filter(candidate -> candidate.supports(format))
                .findFirst()
                .orElseThrow(() -> new ImportFileParseException("找不到對應的檔案解析器"));
        ImportTabularData data = reader.read(path, hardMaxRows);
        return new ImportParseResult(
                data.headers(),
                headerMapper.suggest(dataType, data.headers()),
                data.previewRows(),
                data.totalRows());
    }
}
