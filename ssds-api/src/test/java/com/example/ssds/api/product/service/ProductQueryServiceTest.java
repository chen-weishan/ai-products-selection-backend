package com.example.ssds.api.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.product.dto.ProductResponse;
import com.example.ssds.api.product.dto.ProductSearchRequest;
import com.example.ssds.api.product.dto.ProductListItemResponse;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.LogisticsCondition;
import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.Season;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.dao.ProductListDao;
import com.example.ssds.infra.dao.projection.ProductListRow;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.Supplier;
import com.example.ssds.infra.entity.SourcingCandidate;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.SourcingCandidateRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

class ProductQueryServiceTest {

    private ProductRepository productRepository;
    private ProductListDao productListDao;
    private SourcingCandidateRepository sourcingCandidateRepository;
    private ProductQueryService service;

    @BeforeEach
    void setUp() {
        productListDao = mock(ProductListDao.class);
        productRepository = mock(ProductRepository.class);
        sourcingCandidateRepository = mock(SourcingCandidateRepository.class);
        service = new ProductQueryService(
                productListDao,
                productRepository,
                sourcingCandidateRepository
        );
    }

    @Test
    void getByIdReturnsCompleteProductForEditing() {
        Category category = Category.builder()
                .id(1L)
                .name("食品")
                .build();
        Supplier supplier = Supplier.builder()
                .id(2L)
                .name("測試供應商")
                .build();
        Product product = Product.builder()
                .id(140L)
                .name("完整品項")
                .category(category)
                .supplier(supplier)
                .cost(new BigDecimal("80.00"))
                .suggestedPrice(new BigDecimal("120.00"))
                .marginRate(new BigDecimal("0.3333"))
                .moq(10)
                .season(Season.ALL)
                .status(ProductStatus.EVALUATING)
                .trackType(TrackType.A)
                .logisticsCondition("常溫")
                .shelfLifeDays(180)
                .build();
        product.getKeywords().add(TrendKeyword.builder().id(10L).keyword("露營").build());
        product.getKeywords().add(TrendKeyword.builder().id(11L).keyword("夏季").build());
        product.setCreatedAt(Instant.parse("2026-08-20T01:00:00Z"));
        product.setUpdatedAt(Instant.parse("2026-08-21T01:00:00Z"));

        when(productRepository.findWithDetailsById(140L))
                .thenReturn(Optional.of(product));

        ProductResponse response = service.getById(140L);

        assertEquals(140L, response.id());
        assertEquals("完整品項", response.name());
        assertEquals(1L, response.categoryId());
        assertEquals("食品", response.categoryName());
        assertEquals(2L, response.supplierId());
        assertEquals("測試供應商", response.supplierName());
        assertEquals(new BigDecimal("0.3333"), response.marginRate());
        assertEquals(Set.of(LogisticsCondition.NORMAL), response.logisticsConditions());
        assertEquals(180, response.shelfLifeDays());
        assertEquals(Set.of(10L, 11L), response.keywordIds());
    }

    @Test
    void getByIdWhenProductDoesNotExistReturnsNotFound() {
        when(productRepository.findWithDetailsById(999L))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.getById(999L)
        );

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, exception.getErrorCode().getHttpStatus());
        assertEquals("找不到指定的品項：999", exception.getMessage());
    }

    @Test
    void searchTrackBHidesScoreAndReturnsTimeGap() {
        ProductListRow row = new ProductListRow(
                200L,
                "B 軌品項",
                1L,
                "食品",
                null,
                null,
                null,
                null,
                null,
                new BigDecimal("88.0"),
                Grade.A,
                21,
                TrackType.B,
                SourcingStatus.SOURCING,
                ProductStatus.EVALUATING,
                LastScoringStatus.INSUFFICIENT_DATA,
                Instant.parse("2026-08-20T01:00:00Z"),
                false,
                Instant.parse("2026-08-21T01:00:00Z")
        );
        when(productListDao.search(any()))
                .thenReturn(new PageImpl<>(List.of(row)));

        ProductSearchRequest request = new ProductSearchRequest(
                null,
                null,
                null,
                TrackType.B,
                null,
                null,
                null,
                null,
                null,
                null
        );

        ProductListItemResponse response = service.search(
                request,
                PageRequest.of(
                        0,
                        20,
                        Sort.by(Sort.Direction.ASC, "timeGapDays")
                )
        ).content().getFirst();

        assertNull(response.latestScore());
        assertNull(response.grade());
        assertEquals(21, response.timeGapDays());
        assertEquals(LastScoringStatus.INSUFFICIENT_DATA, response.lastScoringStatus());
        assertEquals(
                Instant.parse("2026-08-20T01:00:00Z"),
                response.lastScoringAttemptedAt()
        );
    }

    @Test
    void getByIdTrackBReturnsOptionalPricingAndTimeGapForEditing() {
        Product product = Product.builder()
                .id(201L)
                .name("B 軌詳情")
                .category(Category.builder().id(1L).name("食品").build())
                .cost(new BigDecimal("80.00"))
                .suggestedPrice(new BigDecimal("120.00"))
                .marginRate(new BigDecimal("0.3333"))
                .status(ProductStatus.EVALUATING)
                .trackType(TrackType.B)
                .sourcingStatus(SourcingStatus.SOURCING)
                .build();
        SourcingCandidate candidate = SourcingCandidate.builder()
                .product(product)
                .leadTimeDays(20)
                .timeGapDays(12)
                .build();
        when(productRepository.findWithDetailsById(201L))
                .thenReturn(Optional.of(product));
        when(sourcingCandidateRepository.findByProductId(201L))
                .thenReturn(Optional.of(candidate));

        ProductResponse response = service.getById(201L);

        assertEquals(new BigDecimal("80.00"), response.cost());
        assertEquals(new BigDecimal("120.00"), response.suggestedPrice());
        assertEquals(new BigDecimal("0.3333"), response.marginRate());
        assertEquals(12, response.timeGapDays());
    }

    @Test
    void searchTrackBWithDefaultScoreSortUsesTimeGapAscending() {
        when(productListDao.search(any()))
                .thenReturn(new PageImpl<>(List.of()));
        ProductSearchRequest request = new ProductSearchRequest(
                null, null, null, TrackType.B, null,
                null, null, null, null, null
        );

        service.search(
                request,
                PageRequest.of(0, 20, Sort.Direction.DESC, "latestScore")
        );

        ArgumentCaptor<com.example.ssds.infra.dao.query.ProductListCriteria> captor =
                ArgumentCaptor.forClass(
                        com.example.ssds.infra.dao.query.ProductListCriteria.class);
        verify(productListDao).search(captor.capture());
        assertEquals("timeGapDays", captor.getValue().sortField());
        assertEquals(true, captor.getValue().ascending());
    }
}
