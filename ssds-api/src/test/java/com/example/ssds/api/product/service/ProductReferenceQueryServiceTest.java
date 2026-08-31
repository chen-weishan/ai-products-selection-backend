package com.example.ssds.api.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.product.dto.CategoryTreeResponse;
import com.example.ssds.api.product.dto.SupplierResponse;
import com.example.ssds.api.product.dto.TrendKeywordResponse;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.FestivalCalendar;
import com.example.ssds.infra.entity.Supplier;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.FestivalCalendarRepository;
import com.example.ssds.infra.repository.SupplierRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductReferenceQueryServiceTest {

    private CategoryRepository categoryRepository;
    private SupplierRepository supplierRepository;
    private FestivalCalendarRepository festivalCalendarRepository;
    private TrendKeywordRepository trendKeywordRepository;
    private ProductReferenceQueryService service;

    @BeforeEach
    void setUp() {
        categoryRepository = mock(CategoryRepository.class);
        supplierRepository = mock(SupplierRepository.class);
        festivalCalendarRepository = mock(FestivalCalendarRepository.class);
        trendKeywordRepository = mock(TrendKeywordRepository.class);
        service = new ProductReferenceQueryService(
                categoryRepository,
                festivalCalendarRepository,
                supplierRepository,
                trendKeywordRepository
        );
    }

    @Test
    void categoryTreeIsSortedBySortOrderAndName() {
        Category drinks = category(3L, "飲料", 2);
        Category snacks = category(2L, "零食", 1);
        Category cookies = category(5L, "餅乾", 2);
        Category candy = category(4L, "糖果", 1);
        snacks.getChildren().addAll(List.of(cookies, candy));
        when(categoryRepository.findTreeWithChildren())
                .thenReturn(List.of(drinks, snacks));

        List<CategoryTreeResponse> result = service.getCategoryTree();

        assertEquals(List.of("零食", "飲料"), result.stream()
                .map(CategoryTreeResponse::name)
                .toList());
        assertEquals(List.of("糖果", "餅乾"), result.getFirst().children()
                .stream()
                .map(CategoryTreeResponse::name)
                .toList());
    }

    @Test
    void blankSupplierKeywordReturnsAllSuppliers() {
        Supplier supplier = Supplier.builder()
                .id(1L)
                .name("京都食品")
                .contact("王小姐")
                .build();
        when(supplierRepository.findAllByOrderByNameAsc())
                .thenReturn(List.of(supplier));

        List<SupplierResponse> result = service.getSuppliers("  ");

        assertEquals(1, result.size());
        assertEquals("京都食品", result.getFirst().name());
        assertEquals("王小姐", result.getFirst().contact());
    }

    @Test
    void supplierKeywordIsTrimmedBeforeSearch() {
        when(supplierRepository
                .findByNameContainingIgnoreCaseOrderByNameAsc("京都"))
                .thenReturn(List.of());

        service.getSuppliers("  京都  ");

        verify(supplierRepository)
                .findByNameContainingIgnoreCaseOrderByNameAsc("京都");
    }

    @Test
    void trendKeywordsCanFilterByTextAndEnabledState() {
        TrendKeyword keyword = TrendKeyword.builder()
                .id(10L)
                .keyword("抹茶餅乾")
                .geo("TW")
                .enabled(true)
                .build();
        when(trendKeywordRepository
                .findByKeywordContainingIgnoreCaseAndEnabledOrderByKeywordAsc(
                        "抹茶",
                        true
                ))
                .thenReturn(List.of(keyword));

        List<TrendKeywordResponse> result = service.getTrendKeywords(
                " 抹茶 ",
                true
        );

        assertEquals(1, result.size());
        assertEquals("抹茶餅乾", result.getFirst().keyword());
        assertEquals(true, result.getFirst().enabled());
    }

    @Test
    void trendKeywordsWithoutFiltersReturnsAllInRepositoryOrder() {
        when(trendKeywordRepository.findAllByOrderByKeywordAsc())
                .thenReturn(List.of());

        service.getTrendKeywords(null, null);

        verify(trendKeywordRepository).findAllByOrderByKeywordAsc();
    }

    @Test
    void festivalsAreDeduplicatedAcrossYears() {
        when(festivalCalendarRepository.findAllByOrderByFestivalNameAscYearDesc())
                .thenReturn(List.of(
                        FestivalCalendar.builder()
                                .festivalCode("MID_AUTUMN")
                                .festivalName("中秋節")
                                .year((short) 2027)
                                .build(),
                        FestivalCalendar.builder()
                                .festivalCode("MID_AUTUMN")
                                .festivalName("中秋節")
                                .year((short) 2026)
                                .build()
                ));

        var result = service.getFestivals();

        assertEquals(1, result.size());
        assertEquals("MID_AUTUMN", result.getFirst().festivalCode());
    }

    private Category category(Long id, String name, int sortOrder) {
        return Category.builder()
                .id(id)
                .name(name)
                .sortOrder(sortOrder)
                .build();
    }
}
