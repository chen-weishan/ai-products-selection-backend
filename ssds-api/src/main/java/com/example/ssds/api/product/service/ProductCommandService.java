package com.example.ssds.api.product.service;

import com.example.ssds.api.common.error.ApiErrorCode;
import com.example.ssds.api.common.error.ApiException;
import com.example.ssds.api.common.response.ApiErrorResponse.FieldError;
import com.example.ssds.api.product.dto.ProductCreateRequest;
import com.example.ssds.api.product.dto.ProductCreateResponse;
import com.example.ssds.api.product.dto.ProductResponse;
import com.example.ssds.api.product.dto.ProductUpdateRequest;
import com.example.ssds.api.product.dto.ProductUpdateResponse;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.Season;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.Supplier;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.SupplierRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import java.math.BigDecimal;
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

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final TrendKeywordRepository trendKeywordRepository;

    public ProductCommandService(
            ProductRepository productRepository,
            CategoryRepository categoryRepository,
            SupplierRepository supplierRepository,
            TrendKeywordRepository trendKeywordRepository
    ) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.supplierRepository = supplierRepository;
        this.trendKeywordRepository = trendKeywordRepository;
    }

    /** 新增品項，重複名稱僅回傳警告，不阻擋儲存。 */
    public ProductCreateResponse create(ProductCreateRequest request) {
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
        validatePricing(
                trackType,
                request.cost(),
                request.suggestedPrice()
        );

        Product product = Product.builder()
                .name(name)
                .category(category)
                .supplier(supplier)
                .cost(request.cost())
                .suggestedPrice(request.suggestedPrice())
                .moq(request.moq())
                .season(request.season() == null ? Season.ALL : request.season())
                .targetAudience(normalizeNullable(request.targetAudience()))
                .status(ProductStatus.EVALUATING)
                .trackType(trackType)
                .sourcingStatus(sourcingStatus)
                .logisticsCondition(normalizeNullable(request.logisticsCondition()))
                .shelfLifeDays(request.shelfLifeDays())
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
        return new ProductCreateResponse(
                toResponse(savedProduct),
                warnings
        );
    }

    /**
     * 完整修改品項基本資料。
     *
     * <p>成功修改後重新標記為 EVALUATING，供下一次 A 軌批次評分取件；
     * createdBy 與建立時間仍保留原值。
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
        SourcingStatus sourcingStatus = resolveSourcingStatus(
                trackType,
                request.sourcingStatus()
        );
        validatePricing(
                trackType,
                request.cost(),
                request.suggestedPrice()
        );

        product.setName(name);
        product.setCategory(category);
        product.setSupplier(supplier);
        product.setCost(request.cost());
        product.setSuggestedPrice(request.suggestedPrice());
        product.setMoq(request.moq());
        product.setSeason(request.season() == null ? Season.ALL : request.season());
        product.setTargetAudience(normalizeNullable(request.targetAudience()));
        product.setStatus(ProductStatus.EVALUATING);
        product.setTrackType(trackType);
        product.setSourcingStatus(sourcingStatus);
        product.setLogisticsCondition(normalizeNullable(request.logisticsCondition()));
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
        return new ProductUpdateResponse(
                toResponse(savedProduct),
                warnings
        );
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorCode.RESOURCE_NOT_FOUND,
                        "找不到指定的品項：" + productId
                ));
    }

    private Category findCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorCode.RESOURCE_NOT_FOUND,
                        "找不到指定的類別：" + categoryId
                ));
    }

    private Supplier findSupplier(Long supplierId) {
        if (supplierId == null) {
            return null;
        }

        return supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ApiException(
                        ApiErrorCode.RESOURCE_NOT_FOUND,
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
            throw new ApiException(
                    ApiErrorCode.RESOURCE_NOT_FOUND,
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

    /**
     * A 軌必須具備可計算毛利率的完整定價；B 軌定價可留空。
     *
     * <p>Create 與 Update 共用此入口，避免兩條寫入流程產生不同規則。
     */
    private void validatePricing(
            TrackType trackType,
            BigDecimal cost,
            BigDecimal suggestedPrice
    ) {
        List<FieldError> fieldErrors = new ArrayList<>();

        if (trackType == TrackType.A) {
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

        if (!fieldErrors.isEmpty()) {
            throw new ApiException(
                    ApiErrorCode.VALIDATION_FAILED,
                    "商品資料驗證失敗",
                    fieldErrors
            );
        }
    }

    private ProductResponse toResponse(Product product) {
        Supplier supplier = product.getSupplier();
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
                product.getTargetAudience(),
                product.getStatus(),
                product.getTrackType(),
                product.getSourcingStatus(),
                product.getLogisticsCondition(),
                product.getShelfLifeDays(),
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
}
