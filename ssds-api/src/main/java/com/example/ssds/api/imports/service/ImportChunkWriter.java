package com.example.ssds.api.imports.service;

import com.example.ssds.infra.dao.BulkImportDao;
import com.example.ssds.infra.entity.AudienceSegment;
import com.example.ssds.infra.entity.ImportBatch;
import com.example.ssds.infra.repository.AudienceSegmentRepository;
import com.example.ssds.infra.repository.ImportBatchRepository;
import com.example.ssds.core.domain.TaskStatus;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 每 500 列獨立提交資料、錯誤與進度，讓非同步狀態查詢能看到實際進展。 */
@Service
public class ImportChunkWriter {
    private final BulkImportDao bulkImportDao;
    private final ImportBatchRepository batchRepository;
    private final AudienceSegmentRepository audienceRepository;
    private final ImportDatabaseLimits databaseLimits;
    private final org.springframework.transaction.support.TransactionTemplate transaction;

    public ImportChunkWriter(
            BulkImportDao bulkImportDao,
            ImportBatchRepository batchRepository,
            AudienceSegmentRepository audienceRepository,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            ImportDatabaseLimits databaseLimits
    ) {
        this.bulkImportDao = bulkImportDao;
        this.batchRepository = batchRepository;
        this.audienceRepository = audienceRepository;
        this.databaseLimits = databaseLimits;
        transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(databaseLimits.seconds());
    }

    public void write(Long batchId, ImportWriteChunk chunk) {
        try {
            transaction.executeWithoutResult(status -> writeTransaction(batchId, chunk));
        } catch (org.springframework.dao.DataIntegrityViolationException error) {
            // Failed PostgreSQL transactions must roll back before any row retry.
            // Preserve source order so committed counters remain a valid restart checkpoint.
            for (ImportWriteChunk row : chunk.individualRows()) {
                try {
                    transaction.executeWithoutResult(status -> writeTransaction(batchId, row));
                } catch (org.springframework.dao.DataIntegrityViolationException rowError) {
                    if (row.sources.isEmpty()) throw rowError;
                    var source = row.sources.getFirst();
                    var failed = new ImportWriteChunk();
                    failed.expectedProcessedRows = row.expectedProcessedRows;
                    failed.deadlineNanos = row.deadlineNanos;
                    failed.failedRows = 1;
                    failed.errors.add(new BulkImportDao.ErrorRow(batchId, source.rowNumber(), null,
                            rowError instanceof DuplicateImportRowException
                                    ? "資料已存在，已略過重複列"
                                    : "資料不符合資料庫限制，請確認參照資料與欄位值", source.rawRow()));
                    transaction.executeWithoutResult(status -> writeTransaction(batchId, failed));
                }
            }
        }
    }

    private void writeTransaction(Long batchId, ImportWriteChunk chunk) {
        if (Thread.currentThread().isInterrupted()
                || (chunk.deadlineNanos != 0 && System.nanoTime() - chunk.deadlineNanos >= 0)) {
            throw new java.util.concurrent.CancellationException("匯入執行逾時或已中斷");
        }
        databaseLimits.apply();
        ImportBatch batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> new IllegalStateException("匯入批次不存在：" + batchId));
        if (batch.getStatus() != TaskStatus.RUNNING) {
            throw new java.util.concurrent.CancellationException("匯入批次已不在執行狀態");
        }
        if (batch.getSuccessRows() + batch.getFailRows() != chunk.expectedProcessedRows) {
            throw new StaleImportCheckpointException();
        }
        int inserted = 0;
        if (!chunk.sales.isEmpty()) inserted += bulkImportDao.batchInsertSalesRecords(chunk.sales);
        if (!chunk.reviews.isEmpty()) inserted += bulkImportDao.batchInsertReviews(chunk.reviews);
        if (!chunk.audiences.isEmpty()) {
            inserted += bulkImportDao.batchInsertAudiences(chunk.audiences);
            if (inserted != chunk.audiences.size()) throw new DuplicateImportRowException();
            writeAudienceMixes(chunk.audienceMixes);
        }
        if (!chunk.products.isEmpty()) inserted += bulkImportDao.batchInsertProducts(chunk.products);
        if (!chunk.errors.isEmpty()) bulkImportDao.batchInsertImportErrors(chunk.errors);

        int conflicts = Math.max(0, chunk.validRows() - inserted);
        if (conflicts != 0) throw new DuplicateImportRowException();
        batch.setSuccessRows(batch.getSuccessRows() + inserted);
        batch.setFailRows(batch.getFailRows() + chunk.failedRows + conflicts);
        batchRepository.save(batch);
    }

    private static final class DuplicateImportRowException
            extends org.springframework.dao.DataIntegrityViolationException {
        DuplicateImportRowException() { super("Duplicate import row"); }
    }

    private void writeAudienceMixes(List<ImportWriteChunk.AudienceMixInput> inputs) {
        if (inputs.isEmpty()) return;
        List<String> codes = inputs.stream().map(ImportWriteChunk.AudienceMixInput::audienceCode).toList();
        Map<String, AudienceSegment> byCode = audienceRepository.findByAudienceCodeIn(codes).stream()
                .collect(Collectors.toMap(AudienceSegment::getAudienceCode, Function.identity()));
        List<BulkImportDao.AudienceMixRow> rows = inputs.stream()
                .filter(input -> byCode.containsKey(input.audienceCode()))
                .map(input -> new BulkImportDao.AudienceMixRow(
                        input.categoryId(), byCode.get(input.audienceCode()).getId(), input.share()))
                .toList();
        if (!rows.isEmpty()) bulkImportDao.batchUpsertAudienceMix(rows);
    }
}
