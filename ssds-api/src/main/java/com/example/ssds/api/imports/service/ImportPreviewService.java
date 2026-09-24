package com.example.ssds.api.imports.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.FieldError;
import com.example.ssds.api.imports.dto.ImportPreviewIssue;
import com.example.ssds.api.imports.dto.ImportPreviewRequest;
import com.example.ssds.api.imports.dto.ImportPreviewResponse;
import com.example.ssds.api.imports.dto.ImportPreviewRow;
import com.example.ssds.core.domain.ImportDataType;
import com.example.ssds.core.domain.Season;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.AudienceSegment;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.ImportBatch;
import com.example.ssds.infra.entity.Supplier;
import com.example.ssds.infra.repository.AudienceSegmentRepository;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.ImportBatchRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductReviewRepository;
import com.example.ssds.infra.repository.SupplierRepository;
import com.example.ssds.ingest.importer.ImportFieldRegistry;
import com.example.ssds.ingest.importer.ImportFileParseException;
import com.example.ssds.ingest.importer.ImportFileScanner;
import com.example.ssds.ingest.importer.ImportHeaderMapper;
import com.example.ssds.ingest.importer.ImportSheetHandler;
import com.example.ssds.ingest.importer.ImportStagingStorage;
import com.example.ssds.ingest.importer.ImportSystemField;
import com.example.ssds.ingest.importer.ImportValueType;
import com.example.ssds.ingest.importer.ProductMatchingRule;
import com.example.ssds.ingest.importer.SalesDeduplicationKey;
import com.example.ssds.ingest.importer.StagedImportFile;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** FR-09 預覽：完整串流驗證、統計結果，只回傳前 20 列且不寫入業務表。 */
@Service
public class ImportPreviewService {

    @org.springframework.beans.factory.annotation.Autowired
    private com.example.ssds.infra.dao.ImportIntegrityDao integrity;

    private static final int PREVIEW_ROWS = 20;

    private final ImportBatchRepository batchRepository;
    private final ImportStagingStorage stagingStorage;
    private final ImportFileScanner fileScanner;
    private final ImportFieldRegistry fieldRegistry;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final AudienceSegmentRepository audienceRepository;
    private final ProductReviewRepository reviewRepository;
    private final ImportTransactionExecutor transactions;
    private final ProductMatchingRule productMatchingRule = new ProductMatchingRule();

    public ImportPreviewService(
            ImportBatchRepository batchRepository,
            ImportStagingStorage stagingStorage,
            ImportFileScanner fileScanner,
            ImportFieldRegistry fieldRegistry,
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            SupplierRepository supplierRepository,
            AudienceSegmentRepository audienceRepository,
            ProductReviewRepository reviewRepository,
            ImportTransactionExecutor transactions
    ) {
        this.batchRepository = batchRepository;
        this.stagingStorage = stagingStorage;
        this.fileScanner = fileScanner;
        this.fieldRegistry = fieldRegistry;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.supplierRepository = supplierRepository;
        this.audienceRepository = audienceRepository;
        this.reviewRepository = reviewRepository;
        this.transactions = transactions;
    }

    public ImportPreviewResponse preview(Long batchId, ImportPreviewRequest request) {
        ImportPreviewResponse response = preview(batchId, request, row -> {});
        stagingStorage.saveDraftMapping(batchId, request.mappings());
        return response;
    }

    /** Revalidate the current mapping without writing import_error or changing batch status. */
    public byte[] errorCsv(Long batchId, ImportPreviewRequest request) {
        var batch = transactions.readOnly(() -> batchRepository.findById(batchId)).orElseThrow(() -> new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的匯入批次"));
        var fields = fieldRegistry.fieldsFor(batch.getDataType()).stream()
                .map(ImportSystemField::key).toList();
        var headers = new ArrayList<>(fields);
        headers.add("_import_row_number");
        headers.add("_import_errors");
        var output = new java.io.StringWriter();
        output.write('\ufeff');
        try (var csv = new org.apache.commons.csv.CSVPrinter(output,
                org.apache.commons.csv.CSVFormat.DEFAULT.builder()
                        .setHeader(headers.toArray(String[]::new)).get())) {
            preview(batchId, request, row -> {
                var rejected = row.issues().stream()
                        .filter(issue -> "ERROR".equals(issue.type()))
                        .toList();
                if (rejected.isEmpty()) return;
                var values = new ArrayList<String>();
                for (String field : fields) values.add(row.values().getOrDefault(field, ""));
                values.add(Integer.toString(row.rowNumber()));
                values.add(rejected.stream().map(issue ->
                        (issue.field() == null ? "" : issue.field() + ": ") + issue.message())
                        .distinct().collect(java.util.stream.Collectors.joining("；")));
                try {
                    csv.printRecord(values);
                } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });
        } catch (java.io.IOException error) {
            throw new IllegalStateException("無法建立預覽錯誤檔", error);
        }
        return output.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private ImportPreviewResponse preview(Long batchId, ImportPreviewRequest request,
            java.util.function.Consumer<ImportPreviewRow> rowConsumer) {
        ImportBatch batch = transactions.readOnly(() -> batchRepository.findById(batchId))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的匯入批次"));
        StagedImportFile staged;
        try {
            staged = stagingStorage.findForBatch(batchId);
        } catch (ImportFileParseException exception) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, exception.getMessage());
        }

        ReferenceCatalog references = loadReferences(batch.getDataType());
        PreviewAccumulator accumulator = new PreviewAccumulator(
                batch.getDataType(), request.mappings(), references, rowConsumer);
        accumulator.audiencePlan = audiencePlan(batch, staged.path(), request.mappings(), references);
        accumulator.existingSales = salesCatalog(batch, staged.path(), request.mappings(), references);
        try {
            fileScanner.scan(staged.path(), batch.getFileName(), accumulator);
        } catch (ImportFileParseException exception) {
            throw validation("file", exception.getMessage());
        }
        return accumulator.response(batch);
    }

    Map<String, Integer> validateMappings(
            ImportDataType dataType,
            List<String> headers,
            Map<String, String> mappings
    ) {
        Map<String, Integer> headerIndexes = new HashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            headerIndexes.put(ImportHeaderMapper.normalize(headers.get(index)), index);
        }
        List<ImportSystemField> fields = fieldRegistry.fieldsFor(dataType);
        Map<String, ImportSystemField> allowed = new HashMap<>();
        fields.forEach(field -> allowed.put(field.key(), field));
        Set<String> targets = new HashSet<>();
        Map<String, Integer> targetIndexes = new LinkedHashMap<>();
        List<FieldError> errors = new ArrayList<>();

        mappings.forEach((source, target) -> {
            String normalizedSource = ImportHeaderMapper.normalize(source);
            Integer sourceIndex = headerIndexes.get(normalizedSource);
            if (fieldRegistry.isPersonalDataHeader(dataType, normalizedSource)) {
                errors.add(new FieldError("mappings." + source, "可識別個資欄位不得匯入"));
            } else if (sourceIndex == null) {
                errors.add(new FieldError("mappings." + source, "上傳檔案中不存在此欄位"));
            } else if (!allowed.containsKey(target)) {
                errors.add(new FieldError("mappings." + source, "不是此資料類型可用的系統欄位"));
            } else if (!targets.add(target)) {
                errors.add(new FieldError("mappings." + source, "同一系統欄位不可重複對應"));
            } else {
                targetIndexes.put(target, sourceIndex);
            }
        });
        List<String> missing = fields.stream()
                .filter(ImportSystemField::required)
                .map(ImportSystemField::key)
                .filter(required -> !targets.contains(required))
                .toList();
        if (!missing.isEmpty()) {
            errors.add(new FieldError("mappings", "缺少必填系統欄位：" + String.join(", ", missing)));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "欄位對應驗證失敗", errors);
        }
        return targetIndexes;
    }

    ReferenceCatalog loadReferences(ImportDataType dataType) {
        return transactions.readOnly(() -> loadReferencesTransaction(dataType));
    }

    private ReferenceCatalog loadReferencesTransaction(ImportDataType dataType) {
        List<ProductMatchingRule.ProductCandidate> products = productRepository.findAllWithCategory()
                .stream()
                .map(product -> new ProductMatchingRule.ProductCandidate(
                        product.getId(), product.getName(), product.getCategory().getName()))
                .toList();
        Map<String, List<Category>> categories = new HashMap<>();
        for (Category category : categoryRepository.findAllByDeletedAtIsNull()) {
            categories.computeIfAbsent(key(category.getName()), ignored -> new ArrayList<>())
                    .add(category);
        }
        Map<String, Supplier> suppliers = new HashMap<>();
        supplierRepository.findAllByDeletedAtIsNull().forEach(supplier ->
                suppliers.putIfAbsent(key(supplier.getName()), supplier));
        Map<String, AudienceSegment> audiences = new HashMap<>();
        audienceRepository.findAll().forEach(audience ->
                audiences.putIfAbsent(key(audience.getAudienceCode()), audience));
        Set<String> reviewKeys = new HashSet<>();
        if (dataType == ImportDataType.REVIEW) {
            List<Object[]> keys = reviewRepository.findAllImportDedupKeys();
            if (keys != null) {
                keys.forEach(row -> reviewKeys.add(row[0] + ":" + row[1]));
            }
        }
        return new ReferenceCatalog(products, categories, suppliers, audiences, reviewKeys);
    }

    private final class PreviewAccumulator implements ImportSheetHandler {
        private final ImportDataType dataType;
        private final Map<String, String> mappings;
        private Map<String, Integer> targetIndexes;
        private final ReferenceCatalog references;
        private final Set<String> fileDuplicateKeys = new HashSet<>();
        private final List<ImportPreviewRow> previewRows = new ArrayList<>();
        private final java.util.function.Consumer<ImportPreviewRow> rowConsumer;
        private AudienceImportPlan audiencePlan;
        private Map<String,String> existingSales=Map.of();
        private final Map<String,String> salesPayloads = new HashMap<>();
        private int totalRows;
        private int errorRows;
        private int duplicateRows;

        private PreviewAccumulator(
                ImportDataType dataType,
                Map<String, String> mappings,
                ReferenceCatalog references,
                java.util.function.Consumer<ImportPreviewRow> rowConsumer
        ) {
            this.dataType = dataType;
            this.mappings = mappings;
            this.references = references;
            this.rowConsumer = rowConsumer;
        }

        @Override
        public void onHeaders(List<String> headers) {
            targetIndexes = validateMappings(dataType, headers, mappings);
        }

        @Override
        public void onRow(int rowNumber, List<String> sourceValues) {
            totalRows++;
            Map<String, String> values = mappedValues(sourceValues);
            List<ImportPreviewIssue> issues = validateRow(dataType, values, references);
            if (audiencePlan != null) audiencePlan.addIssue(values, issues);
            if (dataType == ImportDataType.SALES && issues.isEmpty()) checkSales(values,references,salesPayloads,existingSales,issues);
            boolean hasError = issues.stream().anyMatch(issue -> "ERROR".equals(issue.type()));
            if (!hasError) {
                String duplicateKey = duplicateKey(dataType, values, references);
                if (dataType != ImportDataType.SALES && duplicateKey != null && !fileDuplicateKeys.add(duplicateKey)) {
                    issues.add(ImportPreviewIssue.duplicate(null, "與檔案內先前資料重複，匯入時將略過"));
                } else {
                    checkExistingDuplicate(dataType, values, references, issues);
                }
            }
            boolean duplicate = issues.stream().anyMatch(issue -> "DUPLICATE".equals(issue.type()));
            if (hasError) {
                errorRows++;
            } else if (duplicate) {
                duplicateRows++;
            }
            var row = new ImportPreviewRow(rowNumber, Map.copyOf(values), List.copyOf(issues));
            rowConsumer.accept(row);
            if (previewRows.size() < PREVIEW_ROWS) previewRows.add(row);
        }

        private Map<String, String> mappedValues(List<String> sourceValues) {
            Map<String, String> values = new LinkedHashMap<>();
            targetIndexes.forEach((target, index) -> values.put(
                    target,
                    index < sourceValues.size() ? sourceValues.get(index).trim() : ""));
            return values;
        }

        private ImportPreviewResponse response(ImportBatch batch) {
            return new ImportPreviewResponse(
                    batch.getId(),
                    dataType,
                    totalRows,
                    totalRows - errorRows - duplicateRows,
                    errorRows,
                    duplicateRows,
                    batch.isAsync(),
                    List.copyOf(previewRows),
                    audiencePlan==null ? List.of() : audiencePlan.changes(integrity));
        }
    }

    void checkSales(Map<String,String> values,ReferenceCatalog references,Map<String,String> seen,List<ImportPreviewIssue> issues) {
        String id=com.example.ssds.ingest.importer.SalesImportIdentity.key(values,matchProduct(values,references).productId());
        String prior=id==null?null:integrity.existingPayload(id);
        checkSales(values,references,seen,prior==null?Map.of():Map.of(id,prior),issues);
    }
    void checkSales(Map<String,String> values,ReferenceCatalog references,Map<String,String> seen,Map<String,String> existing,List<ImportPreviewIssue> issues) {
        Long productId=matchProduct(values,references).productId();
        String identity=com.example.ssds.ingest.importer.SalesImportIdentity.key(values,productId);
        if(identity==null) return;
        String payload=com.example.ssds.ingest.importer.SalesImportIdentity.payload(values,productId);
        String prior=seen.get(identity);
        if(prior==null) prior=existing.get(identity);
        if(prior==null) seen.put(identity,payload);
        if(prior!=null) issues.add(prior.equals(payload)
                ? ImportPreviewIssue.duplicate("sourceSystem","相同來源識別與內容已存在，將略過")
                : ImportPreviewIssue.error("sourceSystem","相同來源識別已有不同內容，不會自動覆蓋"));
    }

    Map<String,String> salesCatalog(ImportBatch batch,java.nio.file.Path path,Map<String,String> mappings,ReferenceCatalog refs) {
        if(batch.getDataType()!=ImportDataType.SALES || !mappings.containsValue("sourceSystem")) return Map.of();
        var result=new HashMap<String,String>();
        var keys=new HashSet<String>();
        fileScanner.scan(path,batch.getFileName(),new ImportSheetHandler() {
            Map<String,Integer> indexes;
            public void onHeaders(List<String> headers) {indexes=validateMappings(ImportDataType.SALES,headers,mappings);}
            public void onRow(int number,List<String> source) {
                var values=new HashMap<String,String>();
                indexes.forEach((k,i)->values.put(k,i<source.size()?source.get(i).trim():""));
                if(validateRow(ImportDataType.SALES,values,refs).isEmpty()) {
                    String id=com.example.ssds.ingest.importer.SalesImportIdentity.key(values,matchProduct(values,refs).productId());
                    if(id!=null) keys.add(id);
                }
                if(keys.size()>=500) flush();
            }
            private void flush(){result.putAll(integrity.existingPayloads(keys));keys.clear();}
        });
        result.putAll(integrity.existingPayloads(keys));
        return result;
    }

    AudienceImportPlan audiencePlan(ImportBatch batch,java.nio.file.Path path,Map<String,String> mappings,ReferenceCatalog refs) {
        if(batch.getDataType()!=ImportDataType.AUDIENCE) return null;
        var plan=new AudienceImportPlan(this,refs);
        fileScanner.scan(path,batch.getFileName(),new ImportSheetHandler() {
            Map<String,Integer> indexes;
            public void onHeaders(List<String> headers) { indexes=validateMappings(ImportDataType.AUDIENCE,headers,mappings); }
            public void onRow(int number,List<String> source) {
                var values=new LinkedHashMap<String,String>();
                indexes.forEach((key,index)->values.put(key,index<source.size()?source.get(index).trim():""));
                plan.accept(values);
            }
        });
        plan.finish();
        return plan;
    }

    List<ImportPreviewIssue> validateRow(
            ImportDataType dataType,
            Map<String, String> values,
            ReferenceCatalog references
    ) {
        List<ImportPreviewIssue> issues = new ArrayList<>();
        Map<String, ImportSystemField> fieldByKey = new HashMap<>();
        fieldRegistry.fieldsFor(dataType).forEach(field -> fieldByKey.put(field.key(), field));
        values.forEach((field, value) -> validateBasic(fieldByKey.get(field), value, issues));
        fieldRegistry.fieldsFor(dataType).stream()
                .filter(ImportSystemField::required)
                .filter(field -> blank(values.get(field.key())))
                .forEach(field -> addErrorOnce(issues, field.key(), field.label() + "不可空白"));

        switch (dataType) {
            case SALES -> validateSales(values, references, issues);
            case REVIEW -> validateReview(values, references, issues);
            case AUDIENCE -> validateAudience(values, references, issues);
            case PRODUCT -> validateProduct(values, references, issues);
        }
        return issues;
    }

    private void validateBasic(
            ImportSystemField field,
            String value,
            List<ImportPreviewIssue> issues
    ) {
        if (field == null || blank(value)) {
            return;
        }
        try {
            if (field.valueType() == ImportValueType.INTEGER) {
                Integer.parseInt(value);
            } else if (field.valueType() == ImportValueType.LONG) {
                Long.parseLong(value);
            } else if (field.valueType() == ImportValueType.DECIMAL) {
                new BigDecimal(value);
            } else if (field.valueType() == ImportValueType.DATE) {
                LocalDate.parse(value);
            }
        } catch (NumberFormatException | DateTimeParseException exception) {
            addErrorOnce(issues, field.key(), field.label() + "格式不正確");
        }
    }

    private void validateSales(
            Map<String, String> values,
            ReferenceCatalog references,
            List<ImportPreviewIssue> issues
    ) {
        maxLength(values, "productName", 150, issues);
        for (String field : List.of("sourceSystem","orderNo","lineNo","channel","summaryDimension")) maxLength(values,field,200,issues);
        String kind=values.getOrDefault("salesKind", "");
        if (!blank(kind) && !List.of("DETAIL","SUMMARY").contains(kind.toUpperCase(java.util.Locale.ROOT)))
            addErrorOnce(issues,"salesKind","資料粒度只允許 DETAIL 或 SUMMARY");
        boolean identified=!blank(values.get("sourceSystem")) || !blank(values.get("orderNo")) || !blank(values.get("lineNo"));
        if ("SUMMARY".equalsIgnoreCase(kind)) {
            if(blank(values.get("sourceSystem")) || blank(values.get("channel")))
                addErrorOnce(issues,"sourceSystem","每日彙總必須提供來源系統與通路");
            if(!blank(values.get("orderNo")) || !blank(values.get("lineNo")))
                addErrorOnce(issues,"orderNo","每日彙總不可混用訂單明細識別");
        } else if(identified && (blank(values.get("sourceSystem")) || blank(values.get("orderNo")) || blank(values.get("lineNo")))) {
            addErrorOnce(issues,"sourceSystem","訂單明細識別需同時提供來源系統、訂單編號與明細編號");
        }
        maxLength(values, "audienceCode", 24, issues);
        decimalShape(values, "price", 10, 2, issues);
        positive(values, "price", "單價必須大於 0", issues);
        positiveInteger(values, "qty", "數量必須大於 0", issues);
        nonNegativeInteger(values, "impression", "瀏覽數不可小於 0", issues);
        validateCategory(values.get("category"), references, issues);
        String audience = values.get("audienceCode");
        if (!blank(audience) && !references.audiences().containsKey(key(audience))) {
            addErrorOnce(issues, "audienceCode", "客群代碼不存在");
        }
        ProductMatchingRule.ProductMatchResult match = matchProduct(values, references);
        if (match.status() == ProductMatchingRule.ProductMatchStatus.AMBIGUOUS) {
            addErrorOnce(issues, "productName", "品名符合多個品項，請提供品項 ID 或類別");
        } else if (!blank(values.get("productId"))
                && match.status() == ProductMatchingRule.ProductMatchStatus.UNMATCHED) {
            addErrorOnce(issues, "productId", "指定的品項 ID 不存在");
        }
        // 未提供 ID 的 SALES 仍允許找不到品項，保留 product_name_raw 供後續人工對應。
    }

    private void validateReview(
            Map<String, String> values,
            ReferenceCatalog references,
            List<ImportPreviewIssue> issues
    ) {
        maxLength(values, "source", 50, issues);
        decimalShape(values, "rating", 2, 1, issues);
        ProductMatchingRule.ProductMatchResult match = matchProduct(values, references);
        if (match.status() == ProductMatchingRule.ProductMatchStatus.UNMATCHED) {
            addErrorOnce(issues, "productName", "找不到對應品項");
        } else if (match.status() == ProductMatchingRule.ProductMatchStatus.AMBIGUOUS) {
            addErrorOnce(issues, "productName", "品名符合多個品項，請提供品項 ID 或類別");
        }
        BigDecimal rating = decimal(values.get("rating"));
        if (rating != null && (rating.compareTo(BigDecimal.ONE) < 0
                || rating.compareTo(BigDecimal.valueOf(5)) > 0)) {
            addErrorOnce(issues, "rating", "評分必須介於 1 到 5");
        }
    }

    private void validateAudience(
            Map<String, String> values,
            ReferenceCatalog references,
            List<ImportPreviewIssue> issues
    ) {
        maxLength(values, "audienceCode", 24, issues);
        maxLength(values, "name", 50, issues);
        maxLength(values, "note", 255, issues);
        decimalShape(values, "priceMin", 10, 2, issues);
        decimalShape(values, "priceMax", 10, 2, issues);
        decimalShape(values, "share", 4, 3, issues);
        nonNegative(values, "priceMin", "價格帶下限不可小於 0", issues);
        nonNegative(values, "priceMax", "價格帶上限不可小於 0", issues);
        BigDecimal min = decimal(values.get("priceMin"));
        BigDecimal max = decimal(values.get("priceMax"));
        if (min != null && max != null && min.compareTo(max) > 0) {
            addErrorOnce(issues, "priceMax", "價格帶上限不可小於下限");
        }
        BigDecimal share = decimal(values.get("share"));
        if (share != null && (share.signum() < 0 || share.compareTo(BigDecimal.ONE) > 0)) {
            addErrorOnce(issues, "share", "客群佔比必須介於 0 到 1");
        }
        if (blank(values.get("category")) != blank(values.get("share"))) {
            addErrorOnce(issues, "category", "類別與客群佔比必須同時提供");
        }
        validateCategory(values.get("category"), references, issues);
        String action=values.getOrDefault("masterAction", "");
        if(!blank(action) && !List.of("REUSE","UPDATE").contains(action.toUpperCase(java.util.Locale.ROOT)))
            addErrorOnce(issues,"masterAction","客群主檔操作只允許 REUSE 或 UPDATE");
        var existing=references.audiences().get(key(values.get("audienceCode")));
        if(existing!=null && !"UPDATE".equalsIgnoreCase(action) &&
                (!java.util.Objects.equals(existing.getName(),values.get("name"))
                || !sameDecimal(existing.getPriceMin(),min) || !sameDecimal(existing.getPriceMax(),max)
                || !java.util.Objects.equals(java.util.Objects.toString(existing.getNote(),""),values.getOrDefault("note",""))))
            addErrorOnce(issues,"masterAction","既有客群內容不同；如確定更新請填 UPDATE，會影響所有引用品類");
    }

    private boolean sameDecimal(BigDecimal a,BigDecimal b) { return a==null ? b==null : b!=null && a.compareTo(b)==0; }

    private void validateProduct(
            Map<String, String> values,
            ReferenceCatalog references,
            List<ImportPreviewIssue> issues
    ) {
        maxLength(values, "name", 100, issues);
        maxLength(values, "logisticsCondition", 100, issues);
        decimalShape(values, "cost", 10, 2, issues);
        decimalShape(values, "suggestedPrice", 10, 2, issues);
        decimalShape(values, "idealTempMin", 4, 1, issues);
        decimalShape(values, "idealTempMax", 4, 1, issues);
        validateCategory(values.get("category"), references, issues);
        String supplier = values.get("supplier");
        if (!blank(supplier) && !references.suppliers().containsKey(key(supplier))) {
            addErrorOnce(issues, "supplier", "供應商不存在");
        }
        enumValue(values, "trackType", TrackType.class, issues);
        enumValue(values, "season", Season.class, issues);
        nonNegative(values, "cost", "成本不可小於 0", issues);
        nonNegative(values, "suggestedPrice", "建議售價不可小於 0", issues);
        positiveInteger(values, "moq", "最低訂購量必須大於 0", issues);
        positiveInteger(values, "shelfLifeDays", "效期天數必須大於 0", issues);
        BigDecimal cost = decimal(values.get("cost"));
        BigDecimal price = decimal(values.get("suggestedPrice"));
        if (cost != null && price != null && price.compareTo(cost) <= 0) {
            addErrorOnce(issues, "suggestedPrice", "建議售價必須大於成本");
        }
        BigDecimal min = decimal(values.get("idealTempMin"));
        BigDecimal max = decimal(values.get("idealTempMax"));
        if (min != null && max != null && min.compareTo(max) > 0) {
            addErrorOnce(issues, "idealTempMax", "適溫上限不可小於下限");
        }
    }

    private void validateCategory(
            String category,
            ReferenceCatalog references,
            List<ImportPreviewIssue> issues
    ) {
        if (blank(category)) {
            return;
        }
        List<Category> matches = references.categories().getOrDefault(key(category), List.of());
        if (matches.isEmpty()) {
            addErrorOnce(issues, "category", "類別不存在");
        } else if (matches.size() > 1) {
            addErrorOnce(issues, "category", "類別名稱不唯一，無法判定參照");
        }
    }

    void checkExistingDuplicate(
            ImportDataType dataType,
            Map<String, String> values,
            ReferenceCatalog references,
            List<ImportPreviewIssue> issues
    ) {
        if (dataType == ImportDataType.REVIEW) {
            ProductMatchingRule.ProductMatchResult match = matchProduct(values, references);
            String content = values.get("content");
            if (match.productId() != null && !blank(content)
                    && references.reviewKeys().contains(match.productId() + ":" + sha256(content))) {
                issues.add(ImportPreviewIssue.duplicate("content", "評論已存在，匯入時將略過"));
            }

        } else if (dataType == ImportDataType.PRODUCT) {
            boolean exists = references.products().stream().anyMatch(product ->
                    key(product.category()).equals(key(values.get("category")))
                            && key(product.name()).equals(key(values.get("name"))));
            if (exists) {
                issues.add(ImportPreviewIssue.duplicate("name", "同類別已有相同名稱品項"));
            }
        }
    }

    String duplicateKey(
            ImportDataType dataType,
            Map<String, String> values,
            ReferenceCatalog references
    ) {
        if (dataType == ImportDataType.SALES) {
            return com.example.ssds.ingest.importer.SalesImportIdentity.key(values,matchProduct(values,references).productId());
        }
        if (dataType == ImportDataType.REVIEW) {
            ProductMatchingRule.ProductMatchResult match = matchProduct(values, references);
            return match.productId() + ":" + sha256(values.get("content"));
        }
        if (dataType == ImportDataType.AUDIENCE) {
            return "audience:" + key(values.get("audienceCode")) + ":" + key(values.get("category"));
        }
        return "product:" + key(values.get("category")) + ":" + key(values.get("name"));
    }

    ProductMatchingRule.ProductMatchResult matchProduct(
            Map<String, String> values,
            ReferenceCatalog references
    ) {
        return productMatchingRule.match(
                new ProductMatchingRule.ProductMatchInput(
                        longValue(values.get("productId")),
                        values.get("productName"),
                        values.get("category")),
                references.products());
    }

    private void positive(
            Map<String, String> values, String field, String message, List<ImportPreviewIssue> issues
    ) {
        BigDecimal value = decimal(values.get(field));
        if (value != null && value.signum() <= 0) addErrorOnce(issues, field, message);
    }

    private void nonNegative(
            Map<String, String> values, String field, String message, List<ImportPreviewIssue> issues
    ) {
        BigDecimal value = decimal(values.get(field));
        if (value != null && value.signum() < 0) addErrorOnce(issues, field, message);
    }

    private void positiveInteger(
            Map<String, String> values, String field, String message, List<ImportPreviewIssue> issues
    ) {
        Integer value = integer(values.get(field));
        if (value != null && value <= 0) addErrorOnce(issues, field, message);
    }

    private void nonNegativeInteger(
            Map<String, String> values, String field, String message, List<ImportPreviewIssue> issues
    ) {
        Integer value = integer(values.get(field));
        if (value != null && value < 0) addErrorOnce(issues, field, message);
    }

    private void maxLength(
            Map<String, String> values,
            String field,
            int maximum,
            List<ImportPreviewIssue> issues
    ) {
        String value = values.get(field);
        if (value != null && value.length() > maximum) {
            addErrorOnce(issues, field, "長度不可超過 " + maximum + " 字");
        }
    }

    private void decimalShape(
            Map<String, String> values,
            String field,
            int precision,
            int scale,
            List<ImportPreviewIssue> issues
    ) {
        BigDecimal value = decimal(values.get(field));
        if (value == null) return;
        try {
            BigDecimal scaled = value.setScale(scale, RoundingMode.UNNECESSARY);
            int integerDigits = Math.max(0, scaled.precision() - scaled.scale());
            if (integerDigits > precision - scale) {
                addErrorOnce(issues, field, "數值超過允許範圍");
            }
        } catch (ArithmeticException exception) {
            addErrorOnce(issues, field, "小數位數不可超過 " + scale + " 位");
        }
    }

    private <E extends Enum<E>> void enumValue(
            Map<String, String> values,
            String field,
            Class<E> enumClass,
            List<ImportPreviewIssue> issues
    ) {
        String value = values.get(field);
        if (blank(value)) return;
        try {
            Enum.valueOf(enumClass, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            addErrorOnce(issues, field, "列舉值不正確");
        }
    }

    private void addErrorOnce(List<ImportPreviewIssue> issues, String field, String message) {
        boolean exists = issues.stream().anyMatch(issue -> field.equals(issue.field()));
        if (!exists) issues.add(ImportPreviewIssue.error(field, message));
    }

    Integer integer(String value) {
        try {
            return blank(value) ? null : Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    Long longValue(String value) {
        try {
            return blank(value) ? null : Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    BigDecimal decimal(String value) {
        try {
            return blank(value) ? null : new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    LocalDate date(String value) {
        try {
            return blank(value) ? null : LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value.trim()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支援 SHA-256", exception);
        }
    }

    String key(String value) {
        return ImportHeaderMapper.normalize(value);
    }

    boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private BusinessException validation(String field, String message) {
        return new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "匯入預覽驗證失敗",
                List.of(new FieldError(field, message)));
    }

    record ReferenceCatalog(
            List<ProductMatchingRule.ProductCandidate> products,
            Map<String, List<Category>> categories,
            Map<String, Supplier> suppliers,
            Map<String, AudienceSegment> audiences,
            Set<String> reviewKeys
    ) {}
}
