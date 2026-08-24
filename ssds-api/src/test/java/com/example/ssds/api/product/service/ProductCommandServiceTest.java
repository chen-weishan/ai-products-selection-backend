package com.example.ssds.api.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.product.dto.ProductBatchCategoryRequest;
import com.example.ssds.api.product.dto.ProductBatchCategoryResponse;
import com.example.ssds.api.product.dto.ProductCreateRequest;
import com.example.ssds.api.product.dto.ProductCreateResponse;
import com.example.ssds.api.product.dto.ProductUpdateRequest;
import com.example.ssds.api.product.dto.ProductUpdateResponse;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.Season;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.SupplierRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ProductCommandServiceTest {

    private ProductRepository productRepository;
    private CategoryRepository categoryRepository;
    private ProductCommandService service;
    private Category category;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        SupplierRepository supplierRepository = mock(SupplierRepository.class);
        TrendKeywordRepository keywordRepository = mock(TrendKeywordRepository.class);

        service = new ProductCommandService(
                productRepository,
                categoryRepository,
                supplierRepository,
                keywordRepository
        );

        category = Category.builder()
                .id(1L)
                .name("食品")
                .build();
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(productRepository.saveAndFlush(any(Product.class)))
                .thenAnswer(invocation -> {
                    Product product = invocation.getArgument(0);
                    if (product.getId() == null) {
                        product.setId(100L);
                    }
                    product.recalculateMarginRate();
                    return product;
                });
    }

    @Test
    void createTrackAWithValidPricingSucceeds() {
        ProductCreateResponse response = service.create(createRequest(
                TrackType.A,
                null,
                new BigDecimal("80.00"),
                new BigDecimal("120.00")
        ));

        assertEquals(TrackType.A, response.product().trackType());
        assertEquals(new BigDecimal("0.3333"), response.product().marginRate());
        assertEquals(ProductStatus.EVALUATING, response.product().status());
    }

    @Test
    void createWithoutTrackTypeDefaultsToTrackA() {
        ProductCreateResponse response = service.create(createRequest(
                null,
                null,
                new BigDecimal("80.00"),
                new BigDecimal("120.00")
        ));

        assertEquals(TrackType.A, response.product().trackType());
        assertNull(response.product().sourcingStatus());
    }

    @Test
    void createTrackAWithoutCostReturnsValidationFailure() {
        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.create(createRequest(
                        TrackType.A,
                        null,
                        null,
                        new BigDecimal("120.00")
                )));

        assertBadRequest(exception, "cost", "A 軌品項成本不可為空");
        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void createTrackAWithoutSuggestedPriceReturnsValidationFailure() {
        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.create(createRequest(
                        TrackType.A,
                        null,
                        new BigDecimal("80.00"),
                        null
                )));

        assertBadRequest(
                exception,
                "suggestedPrice",
                "A 軌品項建議售價不可為空"
        );
        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void createTrackAWithSuggestedPriceNotGreaterThanCostReturnsValidationFailure() {
        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.create(createRequest(
                        TrackType.A,
                        null,
                        new BigDecimal("120.00"),
                        new BigDecimal("120.00")
                )));

        assertBadRequest(exception, "suggestedPrice", "建議售價必須大於成本");
        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void createTrackBWithoutPricingSucceeds() {
        ProductCreateResponse response = service.create(createRequest(
                TrackType.B,
                SourcingStatus.SOURCING,
                null,
                null
        ));

        assertNull(response.product().cost());
        assertNull(response.product().suggestedPrice());
        assertNull(response.product().marginRate());
    }

    @Test
    void createTrackBWithoutSourcingStatusDefaultsToPending() {
        ProductCreateResponse response = service.create(createRequest(
                TrackType.B,
                null,
                null,
                null
        ));

        assertEquals(SourcingStatus.PENDING, response.product().sourcingStatus());
    }

    @Test
    void createTrackAIgnoresSourcingStatus() {
        ProductCreateResponse response = service.create(createRequest(
                TrackType.A,
                SourcingStatus.SOURCING,
                new BigDecimal("80.00"),
                new BigDecimal("120.00")
        ));

        assertNull(response.product().sourcingStatus());
    }

    @Test
    void updateWithoutTrackTypeKeepsExistingTrackB() {
        Product product = existingProduct(TrackType.B, SourcingStatus.SOURCING);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        ProductUpdateResponse response = service.update(
                product.getId(),
                updateRequest(null, null, null, null)
        );

        assertEquals(TrackType.B, response.product().trackType());
        assertEquals(SourcingStatus.PENDING, response.product().sourcingStatus());
    }

    @Test
    void updateTrackBToARequiresPricing() {
        Product product = existingProduct(TrackType.B, SourcingStatus.SOURCING);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.update(
                        product.getId(),
                        updateRequest(TrackType.A, SourcingStatus.SOURCING, null, null)
                ));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getErrorCode().getHttpStatus());
        assertEquals(2, exception.getFieldErrors().size());
        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void updateTrackBToAClearsSourcingStatus() {
        Product product = existingProduct(TrackType.B, SourcingStatus.SOURCING);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        ProductUpdateResponse response = service.update(
                product.getId(),
                updateRequest(
                        TrackType.A,
                        SourcingStatus.SOURCING,
                        new BigDecimal("80.00"),
                        new BigDecimal("120.00")
                )
        );

        assertEquals(TrackType.A, response.product().trackType());
        assertNull(response.product().sourcingStatus());
    }

    @Test
    void updateTrackAToBWithoutSourcingStatusDefaultsToPending() {
        Product product = existingProduct(TrackType.A, null);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        ProductUpdateResponse response = service.update(
                product.getId(),
                updateRequest(TrackType.B, null, null, null)
        );

        assertEquals(TrackType.B, response.product().trackType());
        assertEquals(SourcingStatus.PENDING, response.product().sourcingStatus());
    }

    @Test
    void createAndUpdateEnterEvaluatingStatus() {
        ProductCreateResponse created = service.create(createRequest(
                TrackType.A,
                null,
                new BigDecimal("80.00"),
                new BigDecimal("120.00")
        ));

        Product product = existingProduct(TrackType.A, null);
        product.setStatus(ProductStatus.LISTED);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        ProductUpdateResponse updated = service.update(
                product.getId(),
                updateRequest(
                        TrackType.A,
                        null,
                        new BigDecimal("90.00"),
                        new BigDecimal("150.00")
                )
        );

        assertEquals(ProductStatus.EVALUATING, created.product().status());
        assertEquals(ProductStatus.EVALUATING, updated.product().status());
    }

    @Test
    void assignCategoryUpdatesAllProductsAndMarksThemForEvaluation() {
        Category targetCategory = Category.builder()
                .id(2L)
                .name("飲品")
                .build();
        Product first = existingProduct(TrackType.A, null);
        first.setId(50L);
        first.setStatus(ProductStatus.LISTED);
        Product second = existingProduct(TrackType.B, SourcingStatus.PENDING);
        second.setId(51L);
        second.setStatus(ProductStatus.WATCHING);

        when(categoryRepository.findById(2L)).thenReturn(Optional.of(targetCategory));
        when(productRepository.findAllById(Set.of(50L, 51L)))
                .thenReturn(List.of(first, second));
        when(productRepository.saveAllAndFlush(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProductBatchCategoryResponse response = service.assignCategory(
                new ProductBatchCategoryRequest(Set.of(50L, 51L), 2L)
        );

        assertEquals(2L, response.categoryId());
        assertEquals("飲品", response.categoryName());
        assertEquals(2, response.updatedCount());
        assertEquals(Set.of(50L, 51L), response.productIds());
        assertEquals(targetCategory, first.getCategory());
        assertEquals(targetCategory, second.getCategory());
        assertEquals(ProductStatus.EVALUATING, first.getStatus());
        assertEquals(ProductStatus.EVALUATING, second.getStatus());
        verify(productRepository).saveAllAndFlush(List.of(first, second));
    }

    @Test
    void assignCategoryWhenAnyProductIsMissingDoesNotUpdateAnyProduct() {
        Category targetCategory = Category.builder()
                .id(2L)
                .name("飲品")
                .build();
        Product found = existingProduct(TrackType.A, null);
        found.setId(50L);
        Category originalCategory = found.getCategory();

        when(categoryRepository.findById(2L)).thenReturn(Optional.of(targetCategory));
        when(productRepository.findAllById(Set.of(50L, 999L)))
                .thenReturn(List.of(found));

        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.assignCategory(
                        new ProductBatchCategoryRequest(Set.of(50L, 999L), 2L)
                ));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("999"));
        assertEquals(originalCategory, found.getCategory());
        verify(productRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void assignCategoryWhenCategoryDoesNotExistDoesNotLoadOrUpdateProducts() {
        when(categoryRepository.findById(999L)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.assignCategory(
                        new ProductBatchCategoryRequest(Set.of(50L), 999L)
                ));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.getErrorCode());
        assertEquals("找不到指定的類別：999", exception.getMessage());
        verify(productRepository, never()).findAllById(any());
        verify(productRepository, never()).saveAllAndFlush(anyList());
    }

    private ProductCreateRequest createRequest(
            TrackType trackType,
            SourcingStatus sourcingStatus,
            BigDecimal cost,
            BigDecimal suggestedPrice
    ) {
        return new ProductCreateRequest(
                "測試商品",
                1L,
                null,
                cost,
                suggestedPrice,
                10,
                Season.ALL,
                trackType,
                sourcingStatus,
                "常溫",
                180,
                Set.of()
        );
    }

    private ProductUpdateRequest updateRequest(
            TrackType trackType,
            SourcingStatus sourcingStatus,
            BigDecimal cost,
            BigDecimal suggestedPrice
    ) {
        return new ProductUpdateRequest(
                "修改後商品",
                1L,
                null,
                cost,
                suggestedPrice,
                20,
                Season.ALL,
                trackType,
                sourcingStatus,
                "冷藏",
                120,
                Set.of()
        );
    }

    private Product existingProduct(
            TrackType trackType,
            SourcingStatus sourcingStatus
    ) {
        return Product.builder()
                .id(50L)
                .name("既有商品")
                .category(category)
                .status(ProductStatus.EVALUATING)
                .trackType(trackType)
                .sourcingStatus(sourcingStatus)
                .build();
    }

    private void assertBadRequest(
            BusinessException exception,
            String field,
            String message
    ) {
        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, exception.getErrorCode().getHttpStatus());
        assertTrue(exception.getFieldErrors().stream().anyMatch(error ->
                error.field().equals(field) && error.message().equals(message)
        ));
    }
}
