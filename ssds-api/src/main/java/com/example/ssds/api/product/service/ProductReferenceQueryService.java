package com.example.ssds.api.product.service;

import com.example.ssds.api.product.dto.CategoryTreeResponse;
import com.example.ssds.api.product.dto.CategoryMarginMedianResponse;
import com.example.ssds.api.product.dto.FestivalOptionResponse;
import com.example.ssds.api.product.dto.SupplierResponse;
import com.example.ssds.api.product.dto.TrendKeywordResponse;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.FestivalCalendar;
import com.example.ssds.infra.entity.Supplier;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.dao.ProductMarginStatisticsDao;
import com.example.ssds.infra.repository.FestivalCalendarRepository;
import com.example.ssds.infra.repository.SupplierRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 品項表單所需的品類、供應商與關鍵字唯讀選項。 */
@Service
@Transactional(readOnly = true)
public class ProductReferenceQueryService {

    private static final Comparator<Category> CATEGORY_ORDER =
            Comparator.comparingInt(Category::getSortOrder)
                    .thenComparing(Category::getName)
                    .thenComparing(Category::getId);

    private final CategoryRepository categoryRepository;
    private final ProductMarginStatisticsDao marginStatisticsDao;
    private final FestivalCalendarRepository festivalCalendarRepository;
    private final SupplierRepository supplierRepository;
    private final TrendKeywordRepository trendKeywordRepository;

    public ProductReferenceQueryService(
            CategoryRepository categoryRepository,
            ProductMarginStatisticsDao marginStatisticsDao,
            FestivalCalendarRepository festivalCalendarRepository,
            SupplierRepository supplierRepository,
            TrendKeywordRepository trendKeywordRepository
    ) {
        this.categoryRepository = categoryRepository;
        this.marginStatisticsDao = marginStatisticsDao;
        this.festivalCalendarRepository = festivalCalendarRepository;
        this.supplierRepository = supplierRepository;
        this.trendKeywordRepository = trendKeywordRepository;
    }

    public List<CategoryTreeResponse> getCategoryTree() {
        return categoryRepository.findTreeWithChildren().stream()
                .sorted(CATEGORY_ORDER)
                .map(this::toCategoryTreeResponse)
                .toList();
    }

    public List<SupplierResponse> getSuppliers(String keyword) {
        String normalizedKeyword = normalize(keyword);
        List<Supplier> suppliers = normalizedKeyword == null
                ? supplierRepository.findAllByOrderByNameAsc()
                : supplierRepository
                        .findByNameContainingIgnoreCaseOrderByNameAsc(
                                normalizedKeyword
                        );

        return suppliers.stream()
                .map(this::toSupplierResponse)
                .toList();
    }

    public List<TrendKeywordResponse> getTrendKeywords(
            String keyword,
            Boolean enabled
    ) {
        String normalizedKeyword = normalize(keyword);
        List<TrendKeyword> keywords;

        if (normalizedKeyword != null && enabled != null) {
            keywords = trendKeywordRepository
                    .findByKeywordContainingIgnoreCaseAndEnabledOrderByKeywordAsc(
                            normalizedKeyword,
                            enabled
                    );
        } else if (normalizedKeyword != null) {
            keywords = trendKeywordRepository
                    .findByKeywordContainingIgnoreCaseOrderByKeywordAsc(
                            normalizedKeyword
                    );
        } else if (enabled != null) {
            keywords = trendKeywordRepository
                    .findByEnabledOrderByKeywordAsc(enabled);
        } else {
            keywords = trendKeywordRepository.findAllByOrderByKeywordAsc();
        }

        return keywords.stream()
                .map(this::toTrendKeywordResponse)
                .toList();
    }

    public List<FestivalOptionResponse> getFestivals() {
        LinkedHashMap<String, String> festivals = new LinkedHashMap<>();
        festivalCalendarRepository.findAllByOrderByFestivalNameAscYearDesc()
                .forEach(festival -> festivals.putIfAbsent(
                        festival.getFestivalCode(),
                        festival.getFestivalName()
                ));
        return festivals.entrySet().stream()
                .map(entry -> new FestivalOptionResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    public CategoryMarginMedianResponse getCategoryMarginMedian(Long categoryId) {
        var statistics = marginStatisticsDao.findCategoryStatistics(categoryId)
                .orElseThrow(() -> new com.example.ssds.api.common.error.BusinessException(
                        com.example.ssds.api.common.error.ErrorCode.RESOURCE_NOT_FOUND,
                        "找不到指定的類別：" + categoryId
                ));
        return new CategoryMarginMedianResponse(
                statistics.categoryId(),
                statistics.categoryName(),
                statistics.medianMarginRate(),
                statistics.sampleCount()
        );
    }

    private CategoryTreeResponse toCategoryTreeResponse(Category category) {
        List<CategoryTreeResponse> children = category.getChildren().stream()
                .sorted(CATEGORY_ORDER)
                .map(child -> new CategoryTreeResponse(
                        child.getId(),
                        child.getName(),
                        child.getSortOrder(),
                        List.of()
                ))
                .toList();

        return new CategoryTreeResponse(
                category.getId(),
                category.getName(),
                category.getSortOrder(),
                children
        );
    }

    private SupplierResponse toSupplierResponse(Supplier supplier) {
        return new SupplierResponse(
                supplier.getId(),
                supplier.getName(),
                supplier.getContact(),
                supplier.getPhone(),
                supplier.getNote()
        );
    }

    private TrendKeywordResponse toTrendKeywordResponse(
            TrendKeyword keyword
    ) {
        return new TrendKeywordResponse(
                keyword.getId(),
                keyword.getKeyword(),
                keyword.getGeo(),
                keyword.isEnabled(),
                keyword.getLastFetchedAt()
        );
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
