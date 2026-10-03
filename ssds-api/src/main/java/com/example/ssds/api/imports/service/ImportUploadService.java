package com.example.ssds.api.imports.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.FieldError;
import com.example.ssds.api.imports.dto.ImportUploadResponse;
import com.example.ssds.core.domain.ImportDataType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.ImportBatch;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.ImportBatchRepository;
import com.example.ssds.ingest.importer.ImportFileParseException;
import com.example.ssds.ingest.importer.ImportFileParser;
import com.example.ssds.ingest.importer.ImportFileSizeLimitExceededException;
import com.example.ssds.ingest.importer.ImportParseResult;
import com.example.ssds.ingest.importer.ImportRowLimitExceededException;
import com.example.ssds.ingest.importer.ImportStagingStorage;
import com.example.ssds.ingest.importer.StagedImportFile;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

/** FR-09 上傳：建立批次、暫存檔案、解析欄位並判定同步／非同步。 */
@Service
public class ImportUploadService {

    private final ImportBatchRepository batchRepository;
    private final AppUserRepository appUserRepository;
    private final ImportStagingStorage stagingStorage;
    private final ImportFileParser fileParser;
    private final long syncMaxBytes;
    private final long hardMaxBytes;
    private final int syncMaxRows;

    public ImportUploadService(
            ImportBatchRepository batchRepository,
            AppUserRepository appUserRepository,
            ImportStagingStorage stagingStorage,
            ImportFileParser fileParser,
            @Value("${ssds.import.sync-max-bytes:2MB}") DataSize syncMaxSize,
            @Value("${ssds.import.hard-max-bytes:50MB}") DataSize hardMaxSize,
            @Value("${ssds.import.sync-max-rows:5000}") int syncMaxRows
    ) {
        this.batchRepository = batchRepository;
        this.appUserRepository = appUserRepository;
        this.stagingStorage = stagingStorage;
        this.fileParser = fileParser;
        this.syncMaxBytes = syncMaxSize.toBytes();
        this.hardMaxBytes = hardMaxSize.toBytes();
        this.syncMaxRows = syncMaxRows;
    }

    public ImportUploadResponse upload(
            ImportDataType dataType,
            MultipartFile file,
            String actorEmail
    ) {
        String fileName = validateFile(file);
        AppUser actor = appUserRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.UNAUTHORIZED, "登入使用者不存在或已失效"));
        ImportBatch batch = batchRepository.saveAndFlush(ImportBatch.builder()
                .dataType(dataType)
                .fileName(fileName)
                .fileSize(file.getSize())
                .status(TaskStatus.PENDING)
                .createdBy(actor)
                .build());

        StagedImportFile staged = null;
        try {
            staged = stagingStorage.stageForBatch(batch.getId(), fileName, file.getInputStream());
            ImportParseResult parsed = fileParser.parse(staged.path(), fileName, dataType);
            if (parsed.totalRows() == 0) {
                throw validation("檔案沒有可匯入的資料列");
            }
            boolean async = staged.size() > syncMaxBytes || parsed.totalRows() > syncMaxRows;
            batch.setFileSize(staged.size());
            batch.setTotalRows(parsed.totalRows());
            batch.setAsync(async);
            batchRepository.saveAndFlush(batch);
            return new ImportUploadResponse(
                    batch.getId(),
                    dataType,
                    fileName,
                    staged.size(),
                    parsed.totalRows(),
                    async,
                    parsed.headers(),
                    parsed.suggestions(),
                    java.util.Map.of());
        } catch (IOException exception) {
            cleanupRejectedBatch(batch.getId(), staged);
            throw validation("無法讀取上傳檔案");
        } catch (ImportFileSizeLimitExceededException
                 | ImportRowLimitExceededException
                 | ImportFileParseException exception) {
            cleanupRejectedBatch(batch.getId(), staged);
            throw validation(exception.getMessage());
        } catch (RuntimeException exception) {
            cleanupRejectedBatch(batch.getId(), staged);
            throw exception;
        }
    }

    private String validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw validation("請選擇非空白的 CSV 或 XLSX 檔案");
        }
        if (file.getSize() > hardMaxBytes) {
            throw validation("匯入檔案不可超過 50MB，請分批上傳");
        }
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw validation("檔案名稱不可空白");
        }
        String normalized = original.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (fileName.length() > 255) {
            throw validation("檔案名稱不可超過 255 字");
        }
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".csv") && !lower.endsWith(".xlsx")) {
            throw validation("檔案格式只允許 CSV 或 XLSX");
        }
        return fileName;
    }

    private void cleanupRejectedBatch(Long batchId, StagedImportFile staged) {
        if (staged != null) {
            stagingStorage.delete(staged);
        } else {
            stagingStorage.deleteForBatch(batchId);
        }
        batchRepository.deleteById(batchId);
    }

    private BusinessException validation(String detail) {
        return new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "匯入檔案驗證失敗",
                List.of(new FieldError("file", detail)));
    }
}
