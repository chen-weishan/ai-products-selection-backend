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

/** 一般資料每 500 列提交；客群完整檔案共用交易，避免品類組成分段提交。 */
@Service
public class ImportChunkWriter {
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.ssds.infra.dao.ImportIntegrityDao integrity;
    private final org.springframework.transaction.support.TransactionTemplate audienceTransaction;
    private final ThreadLocal<java.util.Set<Long>> replacedCategories=new ThreadLocal<>();

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
        audienceTransaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        audienceTransaction.setTimeout(1800);
    }

    public void writeAudienceFile(Runnable action) {
        audienceTransaction.executeWithoutResult(status -> {
            integrity.lockAudienceImport();
            replacedCategories.set(new java.util.HashSet<>());
            try { action.run(); } finally { replacedCategories.remove(); }
        });
    }

    public void write(Long batchId, ImportWriteChunk chunk) {
        // One transaction for all accepted audience groups; never commit an incomplete category.
        if(replacedCategories.get()!=null) { writeTransaction(batchId,chunk); return; }
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
                    boolean duplicate=rowError instanceof DuplicateImportRowException
                            || rowError instanceof com.example.ssds.infra.dao.ImportIntegrityDao.DuplicateSaleException;
                    failed.failedRows = duplicate ? 0 : 1;
                    failed.skippedRows = duplicate ? 1 : 0;
                    if(!duplicate) failed.errors.add(new BulkImportDao.ErrorRow(batchId, source.rowNumber(), null,
                            rowError instanceof DuplicateImportRowException
                                    ? "資料已存在，已略過重複列"
                                    : rowError instanceof com.example.ssds.infra.dao.ImportIntegrityDao.ConflictingSaleException
                                        ? rowError.getMessage() : "資料不符合資料庫限制，請確認參照資料與欄位值", source.rawRow()));
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
        if (batch.getSuccessRows() + batch.getFailRows() + batch.getSkippedRows() != chunk.expectedProcessedRows) {
            throw new StaleImportCheckpointException();
        }
        int inserted = 0;
        var identities=new java.util.LinkedHashMap<String,String>();
        for(var identity:chunk.salesIdentities) if(identity.key()!=null) identities.put(identity.key(),identity.payload());
        integrity.reserveSales(batchId,identities);
        if (!chunk.sales.isEmpty()) inserted += bulkImportDao.batchInsertSalesRecords(chunk.sales);
        if (!chunk.reviews.isEmpty()) inserted += bulkImportDao.batchInsertReviews(chunk.reviews);
        if (!chunk.audiences.isEmpty()) {
            inserted += bulkImportDao.batchInsertAudiences(chunk.audiences);
            if (inserted != chunk.audiences.size()) throw new DuplicateImportRowException();
            writeAudienceMixes(chunk.audienceMixes);
        }
        if (!chunk.products.isEmpty()) inserted += bulkImportDao.batchInsertProducts(chunk.products, batchId);
        if (!chunk.errors.isEmpty()) bulkImportDao.batchInsertImportErrors(chunk.errors);

        int conflicts = Math.max(0, chunk.validRows() - inserted);
        if (conflicts != 0) throw new DuplicateImportRowException();
        // 銷售匯入沿用 SalesImportCompletedEvent 的正式批次評分，不再建立 V31 任務；
        // V31 只排入沒有其他正式重算入口的評論等匯入資料，避免同一品項被評分兩次。
        var recalculationProductIds=new java.util.HashSet<Long>();
        chunk.reviews.stream().map(BulkImportDao.ReviewRow::productId)
                .filter(java.util.Objects::nonNull).forEach(recalculationProductIds::add);
        integrity.enqueue(batchId,recalculationProductIds);
        if(!chunk.audiences.isEmpty()) integrity.enqueueAudience(batchId,
                chunk.audiences.stream().map(BulkImportDao.AudienceRow::audienceCode).toList(),
                chunk.audienceMixes.stream().map(ImportWriteChunk.AudienceMixInput::categoryId).toList());
        batch.setSkippedRows(batch.getSkippedRows()+chunk.skippedRows);
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
        for(Long category:inputs.stream().map(ImportWriteChunk.AudienceMixInput::categoryId).distinct().sorted().toList()) {
            if(replacedCategories.get()==null) throw new IllegalStateException("客群組成必須在完整檔案交易內寫入");
            if(replacedCategories.get().add(category)) integrity.clearMix(category);
        }
        List<String> codes = inputs.stream().map(ImportWriteChunk.AudienceMixInput::audienceCode).toList();
        Map<String, AudienceSegment> byCode = audienceRepository.findByAudienceCodeIn(codes).stream()
                .collect(Collectors.toMap(AudienceSegment::getAudienceCode, Function.identity()));
        List<BulkImportDao.AudienceMixRow> rows = inputs.stream()
                .filter(input -> byCode.containsKey(input.audienceCode()))
                .map(input -> new BulkImportDao.AudienceMixRow(
                        input.categoryId(), byCode.get(input.audienceCode()).getId(), input.share()))
                .toList();
        if (rows.size()!=inputs.size()) throw new IllegalStateException("客群主檔不存在，組成更新已回滾");
        if (!rows.isEmpty() && bulkImportDao.batchUpsertAudienceMix(rows)!=rows.size())
            throw new IllegalStateException("客群組成未完整寫入，更新已回滾");
    }
}
