package com.example.ssds.api.product.service;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.FieldError;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.api.product.dto.ProductListItemResponse;
import com.example.ssds.api.product.dto.ProductResponse;
import com.example.ssds.api.product.dto.ProductSearchRequest;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.dao.ProductListDao;
import com.example.ssds.infra.dao.projection.ProductListRow;
import com.example.ssds.infra.dao.query.ProductListCriteria;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.Supplier;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.SourcingCandidateRepository;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductQueryService {

    private static final Set<Integer> ALLOWED_PAGE_SIZES =
            Set.of(20, 50, 100);

    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of(
                    "name",
                    "categoryName",
                    "supplierName",
                    "cost",
                    "suggestedPrice",
                    "marginRate",
                    "latestScore",
                    "grade",
                    "timeGapDays",
                    "trackType",
                    "sourcingStatus",
                    "status",
                    "updatedAt"
            );

    private static final BigDecimal MIN_SCORE =
            BigDecimal.ZERO;

    private static final BigDecimal MAX_SCORE =
            BigDecimal.valueOf(100);

    private final ProductListDao productListDao;
    private final ProductRepository productRepository;
    private final SourcingCandidateRepository sourcingCandidateRepository;

    public ProductQueryService(
            ProductListDao productListDao,
            ProductRepository productRepository,
            SourcingCandidateRepository sourcingCandidateRepository
    ) {
        this.productListDao = productListDao;
        this.productRepository = productRepository;
        this.sourcingCandidateRepository = sourcingCandidateRepository;
    }

    public PageResponse<ProductListItemResponse> search(
            ProductSearchRequest request,
            Pageable pageable
    ) {
        validate(request, pageable);

        SortValue sortValue = parseSort(pageable.getSort());
        if (request.trackType() == TrackType.B
                && "latestScore".equals(sortValue.field())) {
            sortValue = new SortValue("timeGapDays", true);
        }

        ProductListCriteria criteria =
                new ProductListCriteria(
                        normalizeKeyword(request.keyword()),
                        request.categoryId(),
                        request.supplierId(),
                        request.trackType(),
                        request.sourcingStatus(),
                        request.status(),
                        request.grade(),
                        request.minScore(),
                        request.maxScore(),
                        request.hasRisk(),
                        pageable.getPageNumber(),
                        pageable.getPageSize(),
                        sortValue.field(),
                        sortValue.ascending()
                );

        Page<ProductListItemResponse> result =
                productListDao
                        .search(criteria)
                        .map(this::toResponse);

        return PageResponse.from(result);
    }

    /** 取得品項完整資料；Repository 以 EntityGraph 一次載入關聯資料。 */
    public ProductResponse getById(Long productId) {
        Product product = productRepository.findWithDetailsById(productId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "找不到指定的品項：" + productId
                ));

        Supplier supplier = product.getSupplier();
        boolean trackB = product.getTrackType() == TrackType.B;
        Integer timeGapDays = trackB
                ? sourcingCandidateRepository.findByProductId(productId)
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

    private void validate(ProductSearchRequest request, Pageable pageable) {
        if (pageable.getPageNumber() < 0) {
            throw validationException(
                    "page",
                    "page 不可小於 0"
            );
        }

        if (!ALLOWED_PAGE_SIZES.contains(
                pageable.getPageSize()
        )) {
            throw validationException(
                    "size",
                    "size 只允許 20、50 或 100"
            );
        }

        validateScore(
                "minScore",
                request.minScore()
        );

        validateScore(
                "maxScore",
                request.maxScore()
        );

        if (request.minScore() != null
                && request.maxScore() != null
                && request.minScore()
                        .compareTo(request.maxScore()) > 0) {
            throw validationException(
                    "minScore",
                    "minScore 不可大於 maxScore"
            );
        }
    }

    private void validateScore(
            String field,
            BigDecimal score
    ) {
        if (score == null) {
            return;
        }

        if (score.compareTo(MIN_SCORE) < 0
                || score.compareTo(MAX_SCORE) > 0) {
            throw validationException(
                    field,
                    field + " 必須介於 0 到 100"
            );
        }
    }

    private SortValue parseSort(Sort sort) {
        if (sort.isUnsorted()) {
            return new SortValue("latestScore", false);
        }

        List<Sort.Order> orders = sort.stream().toList();
        if (orders.size() != 1) {
            throw validationException(
                    "sort",
                    "一次只允許一個排序欄位"
            );
        }

        Sort.Order order = orders.getFirst();
        String field = order.getProperty();

        if (!ALLOWED_SORT_FIELDS.contains(field)) {
            throw validationException(
                    "sort",
                    "不支援的排序欄位：" + field
            );
        }

        return new SortValue(
                field,
                order.isAscending()
        );
    }

    private ProductListItemResponse toResponse(
            ProductListRow row
    ) {
        /*
         * AC-03-4：B 軌不顯示成本、售價與毛利率。
         */
        boolean trackB =
                row.trackType() == TrackType.B;

        return new ProductListItemResponse(
                row.productId(),
                row.productName(),
                row.categoryId(),
                row.categoryName(),
                row.supplierId(),
                row.supplierName(),
                trackB ? null : row.cost(),
                trackB ? null : row.suggestedPrice(),
                trackB ? null : row.marginRate(),
                trackB ? null : row.latestScore(),
                trackB ? null : row.grade(),
                trackB ? row.timeGapDays() : null,
                row.trackType(),
                row.sourcingStatus(),
                row.status(),
                row.lastScoringStatus(),
                row.lastScoringAttemptedAt(),
                row.hasRisk(),
                row.updatedAt()
        );
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }

        return keyword.trim();
    }

    private BusinessException validationException(
            String field,
            String message
    ) {
        return new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "查詢參數驗證失敗",
                List.of(
                    new FieldError(field, message)
                )
        );
    }

    private record SortValue(
            String field,
            boolean ascending
    ) {
    }
}
