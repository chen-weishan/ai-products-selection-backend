package com.example.ssds.api.imports.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.api.imports.dto.ImportBatchResponse;
import com.example.ssds.infra.entity.ImportError;
import com.example.ssds.infra.repository.ImportBatchRepository;
import com.example.ssds.infra.repository.ImportErrorRepository;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ImportBatchQueryService {
    private final ImportBatchRepository batchRepository;
    private final ImportErrorRepository errorRepository;

    public ImportBatchQueryService(
            ImportBatchRepository batchRepository,
            ImportErrorRepository errorRepository
    ) {
        this.batchRepository = batchRepository;
        this.errorRepository = errorRepository;
    }

    public PageResponse<ImportBatchResponse> list(Pageable pageable) {
        return PageResponse.from(batchRepository.findAllByOrderByCreatedAtDesc(pageable)
                .map(ImportBatchResponse::from));
    }

    public ImportBatchResponse get(Long batchId) {
        return ImportBatchResponse.from(batchRepository.findById(batchId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到匯入批次：" + batchId)));
    }

    public byte[] errorCsv(Long batchId) {
        var batch = batchRepository.findById(batchId).orElseThrow(() ->
                new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到匯入批次：" + batchId));
        try {
            var fields = new com.example.ssds.ingest.importer.ImportFieldRegistry()
                    .fieldsFor(batch.getDataType()).stream().map(field -> field.key()).toList();
            var headers = new java.util.ArrayList<>(fields);
            headers.add("_import_row_number");
            headers.add("_import_errors");
            var grouped = new java.util.LinkedHashMap<Integer, java.util.List<ImportError>>();
            for (var error : errorRepository.findByBatchIdOrderByRowNumberAsc(batchId)) {
                // Batch-level failures have no source row to correct and re-import.
                if (error.getRowNumber() > 0 && error.getRawRow() != null) {
                    grouped.computeIfAbsent(error.getRowNumber(), ignored -> new java.util.ArrayList<>()).add(error);
                }
            }
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            StringWriter output = new StringWriter();
            output.write('\ufeff');
            try (CSVPrinter csv = new CSVPrinter(output, CSVFormat.DEFAULT.builder()
                    .setHeader(headers.toArray(String[]::new))
                    .get())) {
                for (var entry : grouped.entrySet()) {
                    java.util.Map<String, String> values = mapper.readValue(
                            entry.getValue().getFirst().getRawRow(),
                            new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>>() {});
                    var row = new java.util.ArrayList<String>();
                    for (String field : fields) row.add(values.getOrDefault(field, ""));
                    row.add(entry.getKey().toString());
                    row.add(entry.getValue().stream().map(error ->
                            (error.getColumnName() == null ? "" : error.getColumnName() + ": ")
                                    + error.getErrorMessage()).distinct()
                            .collect(java.util.stream.Collectors.joining("；")));
                    csv.printRecord(row);
                }
            }
            return output.toString().getBytes(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("無法建立匯入錯誤檔", exception);
        }
    }
}
