package com.example.ssds.api.imports.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.imports.dto.ImportUploadResponse;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.repository.ImportBatchRepository;
import com.example.ssds.ingest.importer.ImportFileParseException;
import com.example.ssds.ingest.importer.ImportFileScanner;
import com.example.ssds.ingest.importer.ImportHeaderMapper;
import com.example.ssds.ingest.importer.ImportStagingStorage;
import org.springframework.stereotype.Service;

/** Rebuild the upload step for a pending batch after the browser page is reopened. */
@Service
public class ImportPendingResumeService {
    private final ImportBatchRepository batchRepository;
    private final ImportStagingStorage stagingStorage;
    private final ImportFileScanner fileScanner;
    private final ImportHeaderMapper headerMapper;
    private final ImportTransactionExecutor transactions;

    public ImportPendingResumeService(
            ImportBatchRepository batchRepository,
            ImportStagingStorage stagingStorage,
            ImportFileScanner fileScanner,
            ImportHeaderMapper headerMapper,
            ImportTransactionExecutor transactions
    ) {
        this.batchRepository = batchRepository;
        this.stagingStorage = stagingStorage;
        this.fileScanner = fileScanner;
        this.headerMapper = headerMapper;
        this.transactions = transactions;
    }

    public ImportUploadResponse resume(Long batchId) {
        var batch = transactions.readOnly(() -> batchRepository.findById(batchId))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到匯入批次：" + batchId));
        if (batch.getStatus() != TaskStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "此匯入批次已確認或已結束，無法接續欄位對應");
        }
        try {
            var staged = stagingStorage.findForBatch(batchId);
            var headers = fileScanner.readHeaders(staged.path(), batch.getFileName());
            return new ImportUploadResponse(
                    batch.getId(), batch.getDataType(), batch.getFileName(), staged.size(),
                    batch.getTotalRows(), batch.isAsync(), headers,
                    headerMapper.suggest(batch.getDataType(), headers),
                    stagingStorage.loadDraftMapping(batchId));
        } catch (ImportFileParseException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                    "匯入暫存檔已過期或無法讀取，請重新上傳");
        }
    }
}
