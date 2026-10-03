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
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.ssds.infra.dao.ImportIntegrityDao integrity;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.ssds.ingest.importer.ImportStagingStorage stagingStorage;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.ssds.ingest.importer.ImportFileScanner scanner;
    @org.springframework.beans.factory.annotation.Autowired
    private ImportPreviewService validation;

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
        var batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到匯入批次：" + batchId));
        String failureReason = errorRepository
                .findFirstByBatchIdAndRowNumberOrderByIdDesc(batchId, 0)
                .map(error -> publicFailureReason(error.getErrorMessage()))
                .orElse(null);
        boolean hasCorrectableErrors = batch.getFailRows() > 0
                && errorRepository.existsByBatchIdAndRowNumberGreaterThanAndRawRowIsNotNull(batchId, 0);
        boolean downloadable=false;
        if(batch.getStatus()!=com.example.ssds.core.domain.TaskStatus.PENDING
                && batch.getStatus()!=com.example.ssds.core.domain.TaskStatus.RUNNING
                && batch.getTotalRows()>batch.getSuccessRows()+batch.getFailRows()+batch.getSkippedRows()) {
            try {stagingStorage.findForBatch(batchId); stagingStorage.loadMapping(batchId); downloadable=true;}
            catch(com.example.ssds.ingest.importer.ImportFileParseException ignored) {}
        }
        return ImportBatchResponse.from(batch, failureReason, hasCorrectableErrors,downloadable,integrity.recalculationSummary(batchId));
    }

    private String publicFailureReason(String message) {
        if (message == null) return "匯入執行發生未預期錯誤，請稍後重試";
        if (message.contains("排隊")) return "匯入排隊逾時，請重新上傳";
        if (message.contains("暫存檔") || message.contains("欄位對應不存在"))
            return "匯入暫存檔已過期或無法讀取，請重新上傳";
        if (message.contains("逾時") || message.contains("超過允許執行時間")
                || message.contains("已中斷")) return "匯入執行逾時，請分批上傳";
        if (message.contains("列數與上傳時不一致"))
            return "匯入檔案與上傳時不一致，請重新上傳";
        return "匯入執行發生未預期錯誤，請稍後重試";
    }

    @Transactional
    public int retryRecalculation(Long batchId) {
        if(!batchRepository.existsById(batchId)) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"找不到匯入批次");
        return integrity.retry(batchId);
    }

    public byte[] unprocessedCsv(Long batchId) {
        var batch=batchRepository.findById(batchId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"找不到匯入批次"));
        if(batch.getStatus()==com.example.ssds.core.domain.TaskStatus.PENDING || batch.getStatus()==com.example.ssds.core.domain.TaskStatus.RUNNING)
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,"請等待匯入結束後再下載未處理資料");
        try {
            var staged=stagingStorage.findForBatch(batchId);
            var mappings=stagingStorage.loadMapping(batchId);
            var fields=new com.example.ssds.ingest.importer.ImportFieldRegistry().fieldsFor(batch.getDataType()).stream().map(f->f.key()).toList();
            var output=new StringWriter();output.write('\ufeff');
            try(var csv=new CSVPrinter(output,CSVFormat.DEFAULT.builder().setHeader(fields.toArray(String[]::new)).get())) {
                int processed=batch.getSuccessRows()+batch.getFailRows()+batch.getSkippedRows();
                scanner.scan(staged.path(),batch.getFileName(),new com.example.ssds.ingest.importer.ImportSheetHandler() {
                    java.util.Map<String,Integer> indexes;int ordinal;
                    public void onHeaders(java.util.List<String> headers) {indexes=validation.validateMappings(batch.getDataType(),headers,mappings);}
                    public void onRow(int number,java.util.List<String> values) {
                        if(++ordinal<=processed) return;
                        var row=new java.util.ArrayList<String>();
                        for(String field:fields) {Integer index=indexes.get(field);row.add(index==null || index>=values.size()?"":values.get(index));}
                        try {csv.printRecord(row);} catch(IOException error) {throw new java.io.UncheckedIOException(error);}
                    }
                });
            }
            return output.toString().getBytes(StandardCharsets.UTF_8);
        } catch(com.example.ssds.ingest.importer.ImportFileParseException error) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"原始暫存檔已過期或無法讀取，無法產生未處理資料檔");
        } catch(IOException error) {throw new IllegalStateException("無法建立未處理資料檔",error);}
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
