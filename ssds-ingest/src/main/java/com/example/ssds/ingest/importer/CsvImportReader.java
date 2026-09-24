package com.example.ssds.ingest.importer;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/** UTF-8 CSV 串流 reader；不把整份檔案一次載入記憶體。 */
@Component
class CsvImportReader implements ImportTabularReader {

    @Override
    public boolean supports(ImportFileFormat format) {
        return format == ImportFileFormat.CSV;
    }

    @Override
    public void scan(Path path, int hardMaxRows, ImportSheetHandler handler) {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            List<String> headers = new java.util.ArrayList<>(parser.getHeaderNames());
            if (!headers.isEmpty()) {
                headers.set(0, headers.getFirst().replace("\uFEFF", ""));
            }
            validateHeaders(headers);
            handler.onHeaders(List.copyOf(headers));

            int totalRows = 0;
            for (CSVRecord record : parser) {
                totalRows++;
                if (totalRows > hardMaxRows) {
                    throw new ImportRowLimitExceededException(hardMaxRows);
                }
                List<String> row = new java.util.ArrayList<>(headers.size());
                for (int index = 0; index < headers.size(); index++) {
                    row.add(index < record.size() ? record.get(index) : "");
                }
                handler.onRow(Math.toIntExact(record.getRecordNumber() + 1), List.copyOf(row));
            }
        } catch (ImportRowLimitExceededException | ImportFileParseException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new ImportFileParseException("CSV 檔案無法解析", exception);
        }
    }

    private void validateHeaders(List<String> headers) {
        if (headers.isEmpty() || headers.stream().allMatch(String::isBlank)) {
            throw new ImportFileParseException("檔案必須包含標題列");
        }
        Set<String> unique = new HashSet<>();
        for (String header : headers) {
            String normalized = ImportHeaderMapper.normalize(header);
            if (normalized.isBlank()) {
                throw new ImportFileParseException("標題列不可包含空白欄位名稱");
            }
            if (!unique.add(normalized)) {
                throw new ImportFileParseException("標題列包含重複欄位：" + header);
            }
        }
    }
}
