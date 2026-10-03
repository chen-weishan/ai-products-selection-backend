package com.example.ssds.api.imports.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.imports.dto.ImportConfirmRequest;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.ImportBatch;
import com.example.ssds.infra.entity.ImportError;
import com.example.ssds.infra.repository.ImportBatchRepository;
import com.example.ssds.infra.repository.ImportErrorRepository;
import com.example.ssds.ingest.importer.ImportFileParseException;
import com.example.ssds.ingest.importer.ImportFileScanner;
import com.example.ssds.ingest.importer.ImportStagingStorage;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** 鎖定並驅動 ImportBatch 狀態，防止重複 confirm 造成重複寫入。 */
@Service
public class ImportBatchLifecycleService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.ssds.infra.dao.ImportIntegrityDao integrity;

    private final ImportBatchRepository batchRepository;
    private final ImportErrorRepository errorRepository;
    private final ImportStagingStorage stagingStorage;
    private final ImportFileScanner fileScanner;
    private final ImportPreviewService previewService;
    private final ImportTransactionExecutor transactions;

    public ImportBatchLifecycleService(
            ImportBatchRepository batchRepository,
            ImportErrorRepository errorRepository,
            ImportStagingStorage stagingStorage,
            ImportFileScanner fileScanner,
            ImportPreviewService previewService,
            ImportTransactionExecutor transactions
    ) {
        this.batchRepository = batchRepository;
        this.errorRepository = errorRepository;
        this.stagingStorage = stagingStorage;
        this.fileScanner = fileScanner;
        this.previewService = previewService;
        this.transactions = transactions;
    }

    public ImportBatch claim(Long batchId, ImportConfirmRequest request) {
        return transactions.required(() -> claimTransaction(batchId, request));
    }

    private ImportBatch claimTransaction(Long batchId, ImportConfirmRequest request) {
        ImportBatch batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> notFound(batchId));
        if (batch.getStatus() != TaskStatus.PENDING) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "此匯入批次已確認或已結束，不可重複執行");
        }
        try {
            var staged = stagingStorage.findForBatch(batchId);
            var headers = fileScanner.readHeaders(staged.path(), batch.getFileName());
            previewService.validateMappings(batch.getDataType(), headers, request.mappings());
            if (batch.getDataType() == com.example.ssds.core.domain.ImportDataType.SALES) {
                try (var input = java.nio.file.Files.newInputStream(staged.path())) {
                    var digest = java.security.MessageDigest.getInstance("SHA-256");
                    byte[] buffer = new byte[8192];
                    for (int n; (n=input.read(buffer)) != -1;) digest.update(buffer,0,n);
                    String fileHash=java.util.HexFormat.of().formatHex(digest.digest());
                    var parts=new java.util.ArrayList<String>();
                    parts.add(fileHash);
                    new java.util.TreeMap<>(request.mappings()).forEach((k,v)->{parts.add(k);parts.add(v);});
                    integrity.claimFile(batchId,com.example.ssds.ingest.importer.SalesImportIdentity.hash(parts));
                } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) {
                    throw new ImportFileParseException("無法讀取匯入檔案指紋",error);
                } catch (IllegalArgumentException error) {
                    throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE,error.getMessage());
                }
            }
            stagingStorage.saveMapping(batchId, request.mappings());
        } catch (ImportFileParseException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, exception.getMessage());
        }
        errorRepository.deleteByBatchId(batchId);
        batch.setSuccessRows(0);
        batch.setFailRows(0);
        batch.setSkippedRows(0);
        batch.setFinishedAt(null);
        batch.setStatus(TaskStatus.RUNNING);
        return batchRepository.saveAndFlush(batch);
    }

    public ImportBatch finish(Long batchId) {
        return transactions.requiresNew(() -> finishTransaction(batchId));
    }

    private ImportBatch finishTransaction(Long batchId) {
        ImportBatch batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> notFound(batchId));
        if (batch.getStatus() != TaskStatus.RUNNING) {
            return batch;
        }
        if (batch.getFailRows() == 0) {
            batch.setStatus(TaskStatus.SUCCEEDED);
        } else if (batch.getSuccessRows() + batch.getSkippedRows() == 0) {
            batch.setStatus(TaskStatus.FAILED);
        } else {
            batch.setStatus(TaskStatus.PARTIAL);
        }
        batch.setFinishedAt(Instant.now());
        return batchRepository.saveAndFlush(batch);
    }

    public boolean fail(Long batchId, String message) {
        return transactions.requiresNew(() -> failTransaction(batchId, message));
    }

    private boolean failTransaction(Long batchId, String message) {
        ImportBatch batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> notFound(batchId));
        if (batch.getStatus() != TaskStatus.RUNNING) {
            return false;
        }
        batch.setStatus(TaskStatus.FAILED);
        batch.setFinishedAt(Instant.now());
        batchRepository.saveAndFlush(batch);
        String safeMessage = message == null || message.isBlank()
                ? "匯入執行發生未預期錯誤"
                : message.substring(0, Math.min(message.length(), 500));
        errorRepository.save(ImportError.builder()
                .batch(batch)
                .rowNumber(0)
                .errorMessage(safeMessage)
                .build());
        return true;
    }

    public void expireArtifacts(java.util.Set<Long> batchIds) {
        transactions.required(() -> expireArtifactsTransaction(batchIds));
    }

    private void expireArtifactsTransaction(java.util.Set<Long> batchIds) {
        for (Long batchId : batchIds) {
            batchRepository.findByIdForUpdate(batchId).ifPresent(batch -> {
                if (batch.getStatus() == TaskStatus.PENDING) {
                    batch.setStatus(TaskStatus.CANCELLED);
                    batch.setFinishedAt(Instant.now());
                } else if (batch.getStatus() == TaskStatus.RUNNING) {
                    batch.setStatus(TaskStatus.FAILED);
                    batch.setFinishedAt(Instant.now());
                    errorRepository.save(ImportError.builder()
                            .batch(batch)
                            .rowNumber(0)
                            .errorMessage("匯入暫存檔已逾保存期限")
                            .build());
                }
            });
        }
    }

    private BusinessException notFound(Long batchId) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到匯入批次：" + batchId);
    }
}
