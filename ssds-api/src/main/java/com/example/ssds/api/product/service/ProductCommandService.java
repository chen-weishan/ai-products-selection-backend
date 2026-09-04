package com.example.ssds.api.product.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.FieldError;
import com.example.ssds.api.product.dto.ProductBatchCategoryRequest;
import com.example.ssds.api.product.dto.ProductBatchCategoryResponse;
import com.example.ssds.api.product.dto.ProductBatchDisableRequest;
import com.example.ssds.api.product.dto.ProductBatchDisableResponse;
import com.example.ssds.api.product.dto.ProductCreateRequest;
import com.example.ssds.api.product.dto.ProductCreateResponse;
import com.example.ssds.api.product.dto.ProductResponse;
import com.example.ssds.api.product.dto.ProductStatusUpdateRequest;
import com.example.ssds.api.product.dto.ProductStatusUpdateResponse;
import com.example.ssds.api.product.dto.ProductUpdateRequest;
import com.example.ssds.api.product.dto.ProductUpdateResponse;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.Season;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.Supplier;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.SupplierRepository;
import com.example.ssds.infra.repository.SourcingCandidateRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-03 品項新增／修改等寫入操作。 */
@Service
@Transactional
public class ProductCommandService {

    private static final String DUPLICATE_NAME_WARNING =
            "同類別已有相同名稱的品項，資料仍已儲存";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");
    private static final Set<String> EDIT_ROLES = Set.of(
            "ROLE_BUYER", "ROLE_BUYER_LEAD", "ROLE_DATA_ADMIN", "ROLE_SYS_ADMIN"
    );
    private static final Set<String> DECISION_ROLES = Set.of(
            "ROLE_BUYER", "ROLE_BUYER_LEAD", "ROLE_SYS_ADMIN"
    );
    private static final Set<String> REVIEW_ROLES = Set.of(
            "ROLE_BUYER_LEAD", "ROLE_SYS_ADMIN"
    );

    private final ProductRepository productRepository;
    private final ProductScoreRepository productScoreRepository;
    private final SourcingCandidateRepository sourcingCandidateRepository;
    private final AppUserRepository appUserRepository;
    private final AuditLogRepository auditLogRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final TrendKeywordRepository trendKeywordRepository;
    private final ProductSourcingCandidateService sourcingCandidateService;

    public ProductCommandService(
            ProductRepository productRepository,
            ProductScoreRepository productScoreRepository,
            SourcingCandidateRepository sourcingCandidateRepository,
            AppUserRepository appUserRepository,
            AuditLogRepository auditLogRepository,
            CategoryRepository categoryRepository,
            SupplierRepository supplierRepository,
            TrendKeywordRepository trendKeywordRepository,
            ProductSourcingCandidateService sourcingCandidateService
    ) {
        this.productRepository = productRepository;
        this.productScoreRepository = productScoreRepository;
        this.sourcingCandidateRepository = sourcingCandidateRepository;
        this.appUserRepository = appUserRepository;
        this.auditLogRepository = auditLogRepository;
        this.categoryRepository = categoryRepository;
        this.supplierRepository = supplierRepository;
        this.trendKeywordRepository = trendKeywordRepository;
        this.sourcingCandidateService = sourcingCandidateService;
    }

    /** 新增品項，重複名稱僅回傳警告，不阻擋儲存。 */
    public ProductCreateResponse create(
            ProductCreateRequest request,
            String actorEmail
    ) {
        AppUser actor = findActor(actorEmail);
        String name = request.name().trim();
        Category category = findCategory(request.categoryId());
        Supplier supplier = findSupplier(request.supplierId());
        Set<TrendKeyword> keywords = findKeywords(request.resolvedKeywordIds());

        TrackType trackType = request.trackType() == null
                ? TrackType.A
                : request.trackType();
        SourcingStatus sourcingStatus = resolveSourcingStatus(
                trackType,
                request.sourcingStatus()
        );
        validateSubmission(
                trackType,
                request.cost(),
                request.suggestedPrice(),
                keywords,
                request.resolvedSaveAsDraft()
        );
        validateTemperatureRange(
                request.idealTempMin(),
                request.idealTempMax()
        );

        Product product = Product.builder()
                .name(name)
                .category(category)
                .supplier(supplier)
                .cost(request.cost())
                .suggestedPrice(request.suggestedPrice())
                .moq(request.moq())
                .season(request.season() == null ? Season.ALL : request.season())
                .status(request.resolvedSaveAsDraft()
                        ? ProductStatus.DRAFT
                        : ProductStatus.EVALUATING)
                .trackType(trackType)
                .sourcingStatus(sourcingStatus)
                .logisticsCondition(ProductLogisticsConditionMapper.encode(
                        request.logisticsConditions()))
                .idealTempMin(request.idealTempMin())
                .idealTempMax(request.idealTempMax())
                .shelfLifeDays(request.shelfLifeDays())
                .createdBy(actor)
                .keywords(keywords)
                .build();

        List<String> warnings = new ArrayList<>();
        if (productRepository.existsByCategoryIdAndNameIgnoreCase(
                category.getId(),
                name
        )) {
            warnings.add(DUPLICATE_NAME_WARNING);
        }

        Product savedProduct = productRepository.saveAndFlush(product);
        sourcingCandidateService.synchronize(savedProduct);
        return new ProductCreateResponse(
                toResponse(savedProduct),
                warnings
        );
    }

    /**
     * 完整修改品項基本資料。
     *
     * <p>草稿可繼續暫存或在資料完整後送出；非草稿修改不會改變既有狀態。
     */
    public ProductUpdateResponse update(
            Long productId,
            ProductUpdateRequest request
    ) {
        Product product = findProduct(productId);
        String name = request.name().trim();
        Category category = findCategory(request.categoryId());
        Supplier supplier = findSupplier(request.supplierId());
        Set<TrendKeyword> keywords = findKeywords(request.resolvedKeywordIds());

        TrackType trackType = request.trackType() == null
                ? product.getTrackType()
                : request.trackType();
        TrackType previousTrackType = product.getTrackType();
        SourcingStatus sourcingStatus = resolveSourcingStatusForUpdate(
                product,
                trackType,
                request.sourcingStatus()
        );
        boolean saveAsDraft = request.resolvedSaveAsDraft();
        validateDraftOperation(product, saveAsDraft);
        validateSubmission(
                trackType,
                request.cost(),
                request.suggestedPrice(),
                keywords,
                saveAsDraft
        );
        validateTemperatureRange(
                request.idealTempMin(),
                request.idealTempMax()
        );

        product.setName(name);
        product.setCategory(category);
        product.setSupplier(supplier);
        product.setCost(request.cost());
        product.setSuggestedPrice(request.suggestedPrice());
        product.setMoq(request.moq());
        product.setSeason(request.season() == null ? Season.ALL : request.season());
        if (product.getStatus() == ProductStatus.DRAFT && !saveAsDraft) {
            product.setStatus(ProductStatus.EVALUATING);
        }
        validateTrackStatus(product.getStatus(), trackType);
        product.setTrackType(trackType);
        product.setSourcingStatus(sourcingStatus);
        product.setLogisticsCondition(ProductLogisticsConditionMapper.encode(
                request.logisticsConditions()));
        product.setIdealTempMin(request.idealTempMin());
        product.setIdealTempMax(request.idealTempMax());
        product.setShelfLifeDays(request.shelfLifeDays());
        product.getKeywords().clear();
        product.getKeywords().addAll(keywords);

        List<String> warnings = new ArrayList<>();
        if (productRepository.existsDuplicateName(
                category.getId(),
                name,
                productId
        )) {
            warnings.add(DUPLICATE_NAME_WARNING);
        }

        Product savedProduct = productRepository.saveAndFlush(product);
        if (previousTrackType != trackType) {
            productScoreRepository.deactivateAllCurrent(savedProduct.getId());
        }
        sourcingCandidateService.synchronize(savedProduct);
        return new ProductUpdateResponse(
                toResponse(savedProduct),
                warnings
        );
    }

    /**
     * 批次指定品項類別。
     *
     * <p>先確認類別與所有品項都存在，再開始修改；搭配類別層級的
     * {@link Transactional}，任何一步失敗都不會留下部分更新。
     */
    public ProductBatchCategoryResponse assignCategory(
            ProductBatchCategoryRequest request
    ) {
        Category category = findCategory(request.categoryId());
        Set<Long> requestedIds = new LinkedHashSet<>(request.productIds());
        List<Product> products = productRepository.findAllById(requestedIds);

        Set<Long> missingIds = new LinkedHashSet<>(requestedIds);
        products.forEach(product -> missingIds.remove(product.getId()));

        if (!missingIds.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "找不到指定的品項：" + missingIds
            );
        }

        products.forEach(product -> product.setCategory(category));
        productRepository.saveAllAndFlush(products);
        products.stream()
                .filter(product -> product.getTrackType() == TrackType.A)
                .forEach(product -> productScoreRepository.deactivateAllCurrent(product.getId()));
        products.stream()
                .filter(product -> product.getTrackType() == TrackType.B)
                .forEach(sourcingCandidateService::synchronize);

        return new ProductBatchCategoryResponse(
                category.getId(),
                category.getName(),
                products.size(),
                requestedIds
        );
    }

    /** FR-03-2 軟刪除品項，保留其評分、決策及稽核關聯。 */
    public void delete(Long productId, String actorEmail, String sourceIp) {
        Product product = findProduct(productId);
        AppUser actor = findActor(actorEmail);

        product.softDelete(actor);
        productRepository.saveAndFlush(product);
        auditLogRepository.save(AuditLog.builder()
                .user(actor)
                .action("DELETE")
                .entityType("Product")
                .entityId(productId)
                .beforeJson("{\"deleted\":false}")
                .afterJson("{\"deleted\":true}")
                .ip(sourceIp)
                .build());
    }

    /** FR-03-1 批次停用；任一品項不存在時整批不修改。 */
    public ProductBatchDisableResponse disableBatch(
            ProductBatchDisableRequest request,
            String actorEmail,
            String sourceIp
    ) {
        Set<Long> requestedIds = new LinkedHashSet<>(request.productIds());
        List<Product> products = productRepository.findAllById(requestedIds);
        Set<Long> missingIds = new LinkedHashSet<>(requestedIds);
        products.forEach(product -> missingIds.remove(product.getId()));

        if (!missingIds.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "找不到指定的品項：" + missingIds
            );
        }

        AppUser actor = findActor(actorEmail);
        products.forEach(product -> product.softDelete(actor));
        productRepository.saveAllAndFlush(products);
        auditLogRepository.saveAll(products.stream()
                .map(product -> AuditLog.builder()
                        .user(actor)
                        .action("DELETE")
                        .entityType("Product")
                        .entityId(product.getId())
                        .beforeJson("{\"deleted\":false}")
                        .afterJson("{\"deleted\":true}")
                        .ip(sourceIp)
                        .build())
                .toList());

        return new ProductBatchDisableResponse(
                products.size(),
                requestedIds
        );
    }

    /** 依規格書 §7.4 執行狀態轉換，並留下轉換前後的稽核紀錄。 */
    public ProductStatusUpdateResponse changeStatus(
            Long productId,
            ProductStatusUpdateRequest request,
            String actorEmail,
            Set<String> authorities,
            String sourceIp
    ) {
        Product product = findProduct(productId);
        AppUser actor = findActor(actorEmail);
        ProductStatus previousStatus = product.getStatus();
        ProductStatus targetStatus = request.targetStatus();

        validateStatusTransition(product, targetStatus, authorities);

        if (targetStatus == ProductStatus.REJECTED) {
            String reason = normalizeNullable(request.rejectReason());
            if (reason == null || reason.length() < 10) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_FAILED,
                        "商品資料驗證失敗",
                        List.of(new FieldError(
                                "rejectReason",
                                "淘汰原因至少需要 10 個字"
                        ))
                );
            }
            product.setRejectReason(reason);
        } else {
            product.setRejectReason(null);
        }

        if (targetStatus == ProductStatus.LISTED) {
            product.setListedAt(LocalDate.now(BUSINESS_ZONE));
        }
        product.setStatus(targetStatus);
        productRepository.saveAndFlush(product);

        auditLogRepository.save(AuditLog.builder()
                .user(actor)
                .action("UPDATE")
                .entityType("Product")
                .entityId(productId)
                .beforeJson(statusJson(previousStatus))
                .afterJson(statusJson(targetStatus))
                .ip(sourceIp)
                .build());

        return new ProductStatusUpdateResponse(
                productId,
                previousStatus,
                targetStatus,
                product.getRejectReason(),
                product.getListedAt()
        );
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "找不到指定的品項：" + productId
                ));
    }

    private AppUser findActor(String actorEmail) {
        return appUserRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.UNAUTHORIZED,
                        "登入使用者不存在或已失效"
                ));
    }

    private Category findCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "找不到指定的類別：" + categoryId
                ));
    }

    private Supplier findSupplier(Long supplierId) {
        if (supplierId == null) {
            return null;
        }

        return supplierRepository.findById(supplierId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "找不到指定的供應商：" + supplierId
                ));
    }

    private Set<TrendKeyword> findKeywords(Set<Long> keywordIds) {
        if (keywordIds.isEmpty()) {
            return new LinkedHashSet<>();
        }

        List<TrendKeyword> foundKeywords = trendKeywordRepository.findAllById(keywordIds);
        Set<Long> missingIds = new LinkedHashSet<>(keywordIds);
        foundKeywords.forEach(keyword -> missingIds.remove(keyword.getId()));

        if (!missingIds.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "找不到指定的關鍵字：" + missingIds
            );
        }

        return new LinkedHashSet<>(foundKeywords);
    }

    private SourcingStatus resolveSourcingStatus(
            TrackType trackType,
            SourcingStatus sourcingStatus
    ) {
        if (trackType == TrackType.A) {
            return null;
        }
        if (sourcingStatus == null) {
            return SourcingStatus.PENDING;
        }
        return sourcingStatus;
    }

    private SourcingStatus resolveSourcingStatusForUpdate(
            Product product,
            TrackType targetTrackType,
            SourcingStatus requestedStatus
    ) {
        if (targetTrackType == TrackType.A) {
            return null;
        }
        if (requestedStatus != null) {
            return requestedStatus;
        }
        if (product.getTrackType() == TrackType.B
                && product.getSourcingStatus() != null) {
            return product.getSourcingStatus();
        }
        return SourcingStatus.PENDING;
    }

    /**
     * A 軌必須具備可計算毛利率的完整定價；B 軌定價可留空。
     *
     * <p>Create 與 Update 共用此入口，避免兩條寫入流程產生不同規則。
     */
    private void validateSubmission(
            TrackType trackType,
            BigDecimal cost,
            BigDecimal suggestedPrice,
            Set<TrendKeyword> keywords,
            boolean saveAsDraft
    ) {
        List<FieldError> fieldErrors = new ArrayList<>();

        if (!saveAsDraft && trackType == TrackType.A) {
            if (cost == null) {
                fieldErrors.add(new FieldError(
                        "cost",
                        "A 軌品項成本不可為空"
                ));
            }
            if (suggestedPrice == null) {
                fieldErrors.add(new FieldError(
                        "suggestedPrice",
                        "A 軌品項建議售價不可為空"
                ));
            }
        }

        if (cost != null
                && suggestedPrice != null
                && suggestedPrice.compareTo(cost) <= 0) {
            fieldErrors.add(new FieldError(
                        "suggestedPrice",
                        "建議售價必須大於成本"
                ));
        }

        if (!saveAsDraft && trackType == TrackType.B && keywords.isEmpty()) {
            fieldErrors.add(new FieldError(
                    "keywordIds",
                    "B 軌品項至少需要 1 個關鍵字"
            ));
        }

        if (!fieldErrors.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "商品資料驗證失敗",
                    fieldErrors
            );
        }
    }

    private void validateTemperatureRange(
            BigDecimal idealTempMin,
            BigDecimal idealTempMax
    ) {
        if ((idealTempMin == null) != (idealTempMax == null)) {
            String missingField = idealTempMin == null
                    ? "idealTempMin"
                    : "idealTempMax";
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "商品資料驗證失敗",
                    List.of(new FieldError(
                            missingField,
                            "適溫區間上下限必須同時填寫"
                    ))
            );
        }

        if (idealTempMin != null
                && idealTempMax != null
                && idealTempMin.compareTo(idealTempMax) > 0) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_FAILED,
                    "商品資料驗證失敗",
                    List.of(new FieldError(
                            "idealTempMin",
                            "適溫區間下限不可大於上限"
                    ))
            );
        }

    }

    private ProductResponse toResponse(Product product) {
        Supplier supplier = product.getSupplier();
        boolean trackB = product.getTrackType() == TrackType.B;
        Integer timeGapDays = trackB
                ? sourcingCandidateRepository.findByProductId(product.getId())
                        .map(candidate -> candidate.getTimeGapDays())
                        .orElse(null)
                : null;
        Set<Long> keywordIds = product.getKeywords().stream()
                .map(TrendKeyword::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getCategory().getId(),
                product.getCategory().getName(),
                supplier == null ? null : supplier.getId(),
                supplier == null ? null : supplier.getName(),
                product.getCost(),
                product.getSuggestedPrice(),
                product.getMarginRate(),
                product.getMoq(),
                product.getSeason(),
                product.getStatus(),
                product.getRejectReason(),
                product.getListedAt(),
                product.getTrackType(),
                product.getSourcingStatus(),
                ProductLogisticsConditionMapper.decode(product.getLogisticsCondition()),
                product.getIdealTempMin(),
                product.getIdealTempMax(),
                product.getShelfLifeDays(),
                timeGapDays,
                Collections.unmodifiableSet(keywordIds),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }

    private String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void validateDraftOperation(Product product, boolean saveAsDraft) {
        if (saveAsDraft && product.getStatus() != ProductStatus.DRAFT) {
            throw invalidTransition(product.getStatus(), ProductStatus.DRAFT);
        }
    }

    private void validateTrackStatus(ProductStatus status, TrackType trackType) {
        if (trackType == TrackType.B
                && status != ProductStatus.DRAFT
                && status != ProductStatus.EVALUATING) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "B 軌品項狀態固定為 EVALUATING，實際進度請使用 sourcingStatus"
            );
        }
    }

    private void validateStatusTransition(
            Product product,
            ProductStatus targetStatus,
            Set<String> authorities
    ) {
        ProductStatus sourceStatus = product.getStatus();
        if (product.getTrackType() == TrackType.B
                && !(sourceStatus == ProductStatus.DRAFT
                        && targetStatus == ProductStatus.EVALUATING)) {
            throw new BusinessException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "B 軌品項狀態固定為 EVALUATING，實際進度請使用 sourcingStatus"
            );
        }

        Set<String> requiredRoles;
        boolean allowed;
        switch (sourceStatus) {
            case DRAFT -> {
                allowed = targetStatus == ProductStatus.EVALUATING;
                requiredRoles = EDIT_ROLES;
                if (allowed) {
                    validateSubmission(
                            product.getTrackType(),
                            product.getCost(),
                            product.getSuggestedPrice(),
                            product.getKeywords(),
                            false
                    );
                }
            }
            case EVALUATING -> {
                allowed = targetStatus == ProductStatus.WATCHING
                        || targetStatus == ProductStatus.ADOPTED
                        || targetStatus == ProductStatus.REJECTED;
                requiredRoles = DECISION_ROLES;
            }
            case WATCHING -> {
                allowed = targetStatus == ProductStatus.ADOPTED
                        || targetStatus == ProductStatus.REJECTED;
                requiredRoles = DECISION_ROLES;
            }
            case ADOPTED -> {
                allowed = targetStatus == ProductStatus.LISTED;
                requiredRoles = EDIT_ROLES;
            }
            case REJECTED -> {
                allowed = targetStatus == ProductStatus.EVALUATING;
                requiredRoles = REVIEW_ROLES;
            }
            default -> {
                allowed = false;
                requiredRoles = Set.of();
            }
        }

        if (!allowed) {
            throw invalidTransition(sourceStatus, targetStatus);
        }
        if (authorities == null
                || Collections.disjoint(authorities, requiredRoles)) {
            throw new BusinessException(
                    ErrorCode.FORBIDDEN,
                    "目前角色無權執行此狀態轉換"
            );
        }
    }

    private BusinessException invalidTransition(
            ProductStatus sourceStatus,
            ProductStatus targetStatus
    ) {
        return new BusinessException(
                ErrorCode.INVALID_STATE_TRANSITION,
                "不允許將品項狀態由 " + sourceStatus + " 變更為 " + targetStatus
        );
    }

    private String statusJson(ProductStatus status) {
        return "{\"status\":\"" + status.name() + "\"}";
    }
}
