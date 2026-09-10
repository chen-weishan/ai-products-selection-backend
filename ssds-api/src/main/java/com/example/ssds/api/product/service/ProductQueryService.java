package com.example.ssds.api.product.service;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.FieldError;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.api.product.dto.ProductListItemResponse;
import com.example.ssds.api.product.dto.ProductSearchRequest;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.dao.ProductListDao;
import com.example.ssds.infra.dao.projection.ProductListRow;
import com.example.ssds.infra.dao.query.ProductListCriteria;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProductQueryService {

    /** §8.1：回應一律以 +08:00 呈現。 */
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Taipei");

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
                    "trackType",
                    "sourcingStatus",
                    "status",
                    "updatedAt"
            );

    private static final BigDecimal MIN_SCORE =
            BigDecimal.ZERO;

    private static final BigDecimal MAX_SCORE =
            BigDecimal.valueOf(100);

    private static final SortValue DEFAULT_SORT =
            new SortValue("latestScore", false);

    private final ProductListDao productListDao;

    public ProductQueryService(ProductListDao productListDao) {
        this.productListDao = productListDao;
    }

    public PageResponse<ProductListItemResponse> search(
            ProductSearchRequest request,
            Pageable pageable
    ) {
        validate(request);
        validatePageable(pageable);

        SortValue sortValue =
                resolveSort(pageable);

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

    private void validate(ProductSearchRequest request) {
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

    private void validatePageable(Pageable pageable) {
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

    /**
     * 排序方向由 Spring 解析成 Sort.Direction，只需再驗證欄位白名單；
     * 多重排序鍵目前不支援，取第一個。
     */
    private SortValue resolveSort(Pageable pageable) {
        Sort sort = pageable.getSort();

        if (!sort.isSorted()) {
            return DEFAULT_SORT;
        }

        Sort.Order order = sort.iterator().next();

        String field = order.getProperty();
        if (!ALLOWED_SORT_FIELDS.contains(field)) {
            throw validationException(
                    "sort",
                    "不支援的排序欄位：" + field
            );
        }

        return new SortValue(field, order.isAscending());
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
                row.latestScore(),
                row.grade(),
                row.trackType(),
                row.sourcingStatus(),
                row.status(),
                row.hasRisk(),
                toDisplayTime(row.updatedAt())
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

    private static OffsetDateTime toDisplayTime(Instant instant) {
        return instant == null
                ? null
                : instant.atZone(DISPLAY_ZONE).toOffsetDateTime();
    }

    private record SortValue(
            String field,
            boolean ascending
    ) {
    }
}
