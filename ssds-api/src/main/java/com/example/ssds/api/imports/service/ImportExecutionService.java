package com.example.ssds.api.imports.service;

import com.example.ssds.api.imports.dto.ImportPreviewIssue;
import com.example.ssds.api.imports.event.ImportCompletedEvent;
import com.example.ssds.core.domain.ImportDataType;
import com.example.ssds.core.domain.Season;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.dao.BulkImportDao;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.ImportBatch;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.Supplier;
import com.example.ssds.infra.event.SalesImportCompletedEvent;
import com.example.ssds.infra.repository.ImportBatchRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.ingest.importer.ImportFileScanner;
import com.example.ssds.ingest.importer.ImportSheetHandler;
import com.example.ssds.ingest.importer.ImportStagingStorage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** 執行已 claim 的匯入批次；預期資料錯誤逐列記錄，技術錯誤才終止整批。 */
@Service
public class ImportExecutionService {
    private static final Logger log = LoggerFactory.getLogger(ImportExecutionService.class);

    private final ImportBatchRepository batchRepository;
    private final ImportStagingStorage stagingStorage;
    private final ImportFileScanner fileScanner;
    private final ImportPreviewService validationService;
    private final ImportChunkWriter chunkWriter;
    private final ImportBatchLifecycleService lifecycleService;
    private final ProductRepository productRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApplicationEventPublisher eventPublisher;
    private final ImportWorkerLock workerLock;
    private final java.time.Duration queueTimeout;
    private final java.time.Duration executionTimeout;

    public ImportExecutionService(
            ImportBatchRepository batchRepository,
            ImportStagingStorage stagingStorage,
            ImportFileScanner fileScanner,
            ImportPreviewService validationService,
            ImportChunkWriter chunkWriter,
            ImportBatchLifecycleService lifecycleService,
            ProductRepository productRepository,
            ApplicationEventPublisher eventPublisher,
            ImportWorkerLock workerLock,
            @org.springframework.beans.factory.annotation.Value("${ssds.import.queue-timeout:10m}") java.time.Duration queueTimeout,
            @org.springframework.beans.factory.annotation.Value("${ssds.import.async-timeout:30m}") java.time.Duration executionTimeout
    ) {
        this.batchRepository = batchRepository;
        this.stagingStorage = stagingStorage;
        this.fileScanner = fileScanner;
        this.validationService = validationService;
        this.chunkWriter = chunkWriter;
        this.lifecycleService = lifecycleService;
        this.productRepository = productRepository;
        this.eventPublisher = eventPublisher;
        this.workerLock = workerLock;
        this.queueTimeout = queueTimeout;
        this.executionTimeout = executionTimeout;
    }

    public void execute(Long batchId) {
        runOwned(batchId, false, null);
    }

    public void expireQueued(Long batchId) {
        runOwned(batchId, true, null);
    }

    public void expireQueued(Long batchId, java.time.Instant queuedAt) {
        runOwned(batchId, true, queuedAt);
    }

    private void runOwned(Long batchId, boolean expireOnly, java.time.Instant queuedAt) {
        try (var lease = workerLock.tryAcquire(batchId)) {
            if (lease == null) return; // Another process owns this batch; never alter its state.
            executeOwned(batchId, lease, expireOnly, queuedAt);
        } catch (Exception error) {
            // DB outage or lost lock: retain RUNNING for a later recovery poll.
            log.warn("FR-09 worker unavailable; will retry batchId={}", batchId, error);
        }
    }

    private void executeOwned(Long batchId, ImportWorkerLock.Lease lease, boolean expireOnly,
            java.time.Instant queuedAt) throws java.sql.SQLException {
        Set<Long> affectedProductIds = new HashSet<>();
        Set<ProductKey> importedProductKeys = new HashSet<>();
        try {
            ImportBatch batch = batchRepository.findById(batchId)
                    .orElseThrow(() -> new IllegalStateException("匯入批次不存在：" + batchId));
            if (batch.getStatus() != com.example.ssds.core.domain.TaskStatus.RUNNING) return;
            int resumeAfterRows = batch.getSuccessRows() + batch.getFailRows() + batch.getSkippedRows();
            if ((resumeAfterRows == 0 && stagingStorage.mappingSavedAt(batchId)
                    .plus(queueTimeout).isBefore(java.time.Instant.now()))
                    || (queuedAt != null && queuedAt.plus(queueTimeout).isBefore(java.time.Instant.now()))) {
                throw new java.util.concurrent.CancellationException("匯入排隊超過允許時間 " + queueTimeout);
            }
            if (expireOnly) return;
            var staged = stagingStorage.findForBatch(batchId);
            Map<String, String> mappings = stagingStorage.loadMapping(batchId);
            var references = validationService.loadReferences(batch.getDataType());
            Long actorId = batch.getCreatedBy() == null ? null : batch.getCreatedBy().getId();

            ImportHandler handler = new ImportHandler(
                    batch, mappings, references, actorId, resumeAfterRows,
                    affectedProductIds, importedProductKeys, lease);
            if (batch.getDataType() == ImportDataType.AUDIENCE) {
                chunkWriter.writeAudienceFile(() -> {
                    var freshReferences=validationService.loadReferences(batch.getDataType());
                    ImportHandler audienceHandler=new ImportHandler(batch,mappings,freshReferences,actorId,resumeAfterRows,
                            affectedProductIds,importedProductKeys,lease);
                    audienceHandler.audiencePlan=validationService.audiencePlan(batch,staged.path(),mappings,freshReferences);
                    fileScanner.scan(staged.path(),batch.getFileName(),audienceHandler);
                    audienceHandler.flush();
                    if(audienceHandler.totalRows!=batch.getTotalRows()) throw new IllegalStateException("匯入檔案列數與上傳時不一致");
                });
                handler.totalRows=batch.getTotalRows();
            } else {
                handler.existingSales=validationService.salesCatalog(batch,staged.path(),mappings,references);
                fileScanner.scan(staged.path(), batch.getFileName(), handler);
                handler.flush();
            }
            if (handler.totalRows != batch.getTotalRows()) {
                throw new IllegalStateException("匯入檔案列數與上傳時不一致");
            }

            if (batch.getDataType() == ImportDataType.PRODUCT && !importedProductKeys.isEmpty()) {
                for (Product product : productRepository.findAllWithCategory()) {
                    ProductKey key = new ProductKey(
                            validationService.key(product.getCategory().getName()),
                            validationService.key(product.getName()));
                    if (importedProductKeys.contains(key)) affectedProductIds.add(product.getId());
                }
            }
            handler.checkDeadline();
            lease.check();
            ImportBatch completed = lifecycleService.finish(batchId);
            publish(completed, affectedProductIds);
        } catch (Exception exception) {
            // A lost connection/ownership must never mark a replacement worker's batch failed.
            boolean interrupted = Thread.interrupted();
            try {
            lease.check();
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof StaleImportCheckpointException) return;
            }
            log.error("FR-09 import failed: batchId={}", batchId, exception);
            if (lifecycleService.fail(batchId, exception.getMessage())) {
                batchRepository.findById(batchId).ifPresent(batch -> publish(batch, affectedProductIds));
            }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    void publish(ImportBatch batch, Set<Long> affectedProductIds) {
        eventPublisher.publishEvent(new ImportCompletedEvent(
                batch.getId(), batch.getDataType(), batch.getStatus(),
                batch.getSuccessRows(), batch.getFailRows(), Set.copyOf(affectedProductIds)));
        if (batch.getDataType() == ImportDataType.SALES
                && batch.getSuccessRows() > 0) {
            eventPublisher.publishEvent(new SalesImportCompletedEvent(batch.getId()));
        }
    }

    private final class ImportHandler implements ImportSheetHandler {
        private final ImportBatch batch;
        private final Map<String, String> mappings;
        private Map<String, Integer> indexes;
        private final ImportPreviewService.ReferenceCatalog references;
        private final Long actorId;
        private final int resumeAfterRows;
        private final Set<Long> affectedProductIds;
        private final Set<ProductKey> importedProductKeys;
        private final Set<String> duplicateKeys = new HashSet<>();
        private ImportWriteChunk chunk = new ImportWriteChunk();
        private int rowsInChunk;
        private int totalRows;
        private AudienceImportPlan audiencePlan;
        private final Map<String,String> salesPayloads=new LinkedHashMap<>();
        private Map<String,String> existingSales=Map.of();
        private final ImportWorkerLock.Lease lease;
        private final long deadline = System.nanoTime() + executionTimeout.toNanos();

        private ImportHandler(
                ImportBatch batch,
                Map<String, String> mappings,
                ImportPreviewService.ReferenceCatalog references,
                Long actorId,
                int resumeAfterRows,
                Set<Long> affectedProductIds,
                Set<ProductKey> importedProductKeys,
                ImportWorkerLock.Lease lease
        ) {
            this.batch = batch;
            this.mappings = mappings;
            this.references = references;
            this.actorId = actorId;
            this.resumeAfterRows = resumeAfterRows;
            this.affectedProductIds = affectedProductIds;
            this.importedProductKeys = importedProductKeys;
            this.lease = lease;
        }

        @Override
        public void onHeaders(List<String> headers) {
            indexes = validationService.validateMappings(batch.getDataType(), headers, mappings);
        }

        @Override
        public void onRow(int rowNumber, List<String> sourceValues) {
            checkDeadline();
            if (Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("匯入工作已逾時取消");
            }
            totalRows++;
            Map<String, String> values = mappedValues(sourceValues);
            if (totalRows <= resumeAfterRows) {
                rememberSkippedRow(values);
                return;
            }
            List<ImportPreviewIssue> issues = new ArrayList<>(
                    validationService.validateRow(batch.getDataType(), values, references));
            if(audiencePlan!=null) audiencePlan.addIssue(values,issues);
            if(batch.getDataType()==ImportDataType.SALES && issues.isEmpty())
                validationService.checkSales(values,references,salesPayloads,existingSales,issues);
            if (issues.isEmpty()) {
                String key = validationService.duplicateKey(batch.getDataType(), values, references);
                if (batch.getDataType()!=ImportDataType.SALES && key != null && !duplicateKeys.add(key)) {
                    issues.add(ImportPreviewIssue.duplicate(null, "與檔案內先前資料重複"));
                } else {
                    validationService.checkExistingDuplicate(
                            batch.getDataType(), values, references, issues);
                }
            }
            if (issues.isEmpty()) {
                try {
                    addValid(values);
                    chunk.sources.add(new BulkImportDao.ErrorRow(
                            batch.getId(), rowNumber, null, "", raw(values)));
                } catch (RuntimeException exception) {
                    issues.add(ImportPreviewIssue.error(null, "資料轉換失敗"));
                }
            }
            if (!issues.isEmpty()) {
                if(issues.stream().allMatch(issue -> "DUPLICATE".equals(issue.type()))) {chunk.skippedRows++; chunk.skippedSourceRows.add(rowNumber); }
                else addErrors(rowNumber, values, issues);
            }
            rowsInChunk++;
            if (rowsInChunk >= BulkImportDao.BATCH_SIZE) flush();
        }

        private Map<String, String> mappedValues(List<String> sourceValues) {
            Map<String, String> values = new LinkedHashMap<>();
            indexes.forEach((target, index) -> values.put(
                    target, index < sourceValues.size() ? sourceValues.get(index).trim() : ""));
            return values;
        }

        private void addValid(Map<String, String> values) {
            switch (batch.getDataType()) {
                case SALES -> addSales(values);
                case REVIEW -> addReview(values);
                case AUDIENCE -> addAudience(values);
                case PRODUCT -> addProduct(values);
            }
        }

        private void addSales(Map<String, String> values) {
            var match = validationService.matchProduct(values, references);
            Long productId = match.productId();
            if (productId != null) affectedProductIds.add(productId);
            chunk.salesIdentities.add(new ImportWriteChunk.SalesIdentity(
                    com.example.ssds.ingest.importer.SalesImportIdentity.key(values,productId),
                    com.example.ssds.ingest.importer.SalesImportIdentity.payload(values,productId)));
            Long categoryId = category(values.get("category"));
            chunk.sales.add(new BulkImportDao.SalesRow(
                    validationService.date(values.get("orderDate")), productId,
                    values.get("productName"), categoryId,
                    validationService.decimal(values.get("price")),
                    validationService.integer(values.get("qty")),
                    validationService.integer(values.get("impression")),
                    emptyToNull(values.get("audienceCode")), batch.getId()));
        }

        private void addReview(Map<String, String> values) {
            Long productId = validationService.matchProduct(values, references).productId();
            affectedProductIds.add(productId);
            String content = values.get("content");
            chunk.reviews.add(new BulkImportDao.ReviewRow(
                    productId, defaultIfBlank(values.get("source"), "IMPORT"), content,
                    validationService.decimal(values.get("rating")),
                    validationService.date(values.get("reviewedAt")),
                    validationService.sha256(content)));
        }

        private void addAudience(Map<String, String> values) {
            String code = values.get("audienceCode");
            var existing=references.audiences().get(validationService.key(code));
            code=existing==null ? code.toUpperCase(java.util.Locale.ROOT) : existing.getAudienceCode();
            chunk.audiences.add(new BulkImportDao.AudienceRow(
                    code, values.get("name"), validationService.decimal(values.get("priceMin")),
                    validationService.decimal(values.get("priceMax")), emptyToNull(values.get("note")),
                    "UPDATE".equalsIgnoreCase(values.get("masterAction"))));
            if (!validationService.blank(values.get("category"))) {
                addProductsInCategory(values.get("category"));
                chunk.audienceMixes.add(new ImportWriteChunk.AudienceMixInput(
                        code, category(values.get("category")),
                        validationService.decimal(values.get("share"))));
            }
        }

        private void addProduct(Map<String, String> values) {
            Long categoryId = category(values.get("category"));
            Supplier supplier = references.suppliers().get(validationService.key(values.get("supplier")));
            BigDecimal cost = validationService.decimal(values.get("cost"));
            BigDecimal price = validationService.decimal(values.get("suggestedPrice"));
            BigDecimal margin = cost == null || price == null || price.signum() == 0
                    ? null : price.subtract(cost).divide(price, 4, RoundingMode.HALF_UP);
            String track = defaultIfBlank(values.get("trackType"), TrackType.A.name())
                    .toUpperCase(java.util.Locale.ROOT);
            String season = defaultIfBlank(values.get("season"), Season.ALL.name())
                    .toUpperCase(java.util.Locale.ROOT);
            chunk.products.add(new BulkImportDao.ProductRow(
                    values.get("name"), categoryId, supplier == null ? null : supplier.getId(),
                    cost, price, margin, validationService.integer(values.get("moq")), season, track,
                    emptyToNull(values.get("logisticsCondition")),
                    validationService.decimal(values.get("idealTempMin")),
                    validationService.decimal(values.get("idealTempMax")),
                    validationService.integer(values.get("shelfLifeDays")), actorId));
            importedProductKeys.add(new ProductKey(
                    validationService.key(values.get("category")),
                    validationService.key(values.get("name"))));
        }

        private Long category(String categoryName) {
            if (validationService.blank(categoryName)) return null;
            List<Category> categories = references.categories()
                    .getOrDefault(validationService.key(categoryName), List.of());
            return categories.size() == 1 ? categories.getFirst().getId() : null;
        }

        /** 重啟恢復時略過已提交列，並重建檔案內去重狀態與事件影響範圍。 */
        private void rememberSkippedRow(Map<String, String> values) {
            List<ImportPreviewIssue> issues = validationService.validateRow(
                    batch.getDataType(), values, references);
            if (issues.isEmpty()) {
                String duplicateKey = validationService.duplicateKey(
                        batch.getDataType(), values, references);
                if (duplicateKey != null) duplicateKeys.add(duplicateKey);
                if(batch.getDataType()==ImportDataType.SALES) {
                    String identity=com.example.ssds.ingest.importer.SalesImportIdentity.key(values,validationService.matchProduct(values,references).productId());
                    if(identity!=null) salesPayloads.putIfAbsent(identity,com.example.ssds.ingest.importer.SalesImportIdentity.payload(values,validationService.matchProduct(values,references).productId()));
                }
            }
            switch (batch.getDataType()) {
                case SALES, REVIEW -> {
                    Long productId = validationService.matchProduct(values, references).productId();
                    if (productId != null) affectedProductIds.add(productId);
                }
                case PRODUCT -> importedProductKeys.add(new ProductKey(
                        validationService.key(values.get("category")),
                        validationService.key(values.get("name"))));
                case AUDIENCE -> addProductsInCategory(values.get("category"));
            }
        }

        private void addProductsInCategory(String categoryName) {
            String categoryKey = validationService.key(categoryName);
            if (categoryKey.isBlank()) return;
            references.products().stream()
                    .filter(product -> validationService.key(product.category()).equals(categoryKey))
                    .map(com.example.ssds.ingest.importer.ProductMatchingRule.ProductCandidate::id)
                    .forEach(affectedProductIds::add);
        }

        private void addErrors(
                int rowNumber,
                Map<String, String> values,
                List<ImportPreviewIssue> issues
        ) {
            chunk.failedRows++;
            String raw = raw(values);
            for (ImportPreviewIssue issue : issues) {
                chunk.errors.add(new BulkImportDao.ErrorRow(
                        batch.getId(), rowNumber, issue.field(), issue.message(), raw));
            }
        }

        private String raw(Map<String, String> values) {
            try {
                return objectMapper.writeValueAsString(values);
            } catch (JsonProcessingException exception) {
                return values.toString();
            }
        }

        private String emptyToNull(String value) {
            return validationService.blank(value) ? null : value;
        }

        private String defaultIfBlank(String value, String fallback) {
            return validationService.blank(value) ? fallback : value.trim();
        }

        private void flush() {
            checkDeadline();
            try { lease.check(); } catch (java.sql.SQLException error) {
                throw new IllegalStateException("匯入執行鎖連線中斷", error);
            }
            chunk.expectedProcessedRows = totalRows - rowsInChunk;
            chunk.deadlineNanos = deadline;
            if (!chunk.isEmpty()) chunkWriter.write(batch.getId(), chunk);
            chunk = new ImportWriteChunk();
            rowsInChunk = 0;
        }

        private void checkDeadline() {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0)
                throw new java.util.concurrent.CancellationException("匯入工作超過允許執行時間 " + executionTimeout);
        }
    }

    private record ProductKey(String category, String name) {}
}
