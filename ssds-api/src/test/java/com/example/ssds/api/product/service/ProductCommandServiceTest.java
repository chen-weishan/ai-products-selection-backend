package com.example.ssds.api.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.product.dto.ProductBatchCategoryRequest;
import com.example.ssds.api.product.dto.ProductBatchCategoryResponse;
import com.example.ssds.api.product.dto.ProductBatchDisableRequest;
import com.example.ssds.api.product.dto.ProductBatchDisableResponse;
import com.example.ssds.api.product.dto.ProductCreateRequest;
import com.example.ssds.api.product.dto.ProductCreateResponse;
import com.example.ssds.api.product.dto.ProductStatusUpdateRequest;
import com.example.ssds.api.product.dto.ProductStatusUpdateResponse;
import com.example.ssds.api.product.dto.ProductUpdateRequest;
import com.example.ssds.api.product.dto.ProductUpdateResponse;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.LogisticsCondition;
import com.example.ssds.core.domain.Season;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.SupplierRepository;
import com.example.ssds.infra.repository.SourcingCandidateRepository;
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
    private ProductScoreRepository productScoreRepository;
    private AppUserRepository appUserRepository;
    private AuditLogRepository auditLogRepository;
    private CategoryRepository categoryRepository;
    private TrendKeywordRepository keywordRepository;
    private ProductSourcingCandidateService sourcingCandidateService;
    private ProductCommandService service;
    private Category category;
    private AppUser createActor;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        productScoreRepository = mock(ProductScoreRepository.class);
        SourcingCandidateRepository sourcingCandidateRepository =
                mock(SourcingCandidateRepository.class);
        appUserRepository = mock(AppUserRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        SupplierRepository supplierRepository = mock(SupplierRepository.class);
        keywordRepository = mock(TrendKeywordRepository.class);
        sourcingCandidateService = mock(ProductSourcingCandidateService.class);

        service = new ProductCommandService(
                productRepository,
                productScoreRepository,
                sourcingCandidateRepository,
                appUserRepository,
                auditLogRepository,
                categoryRepository,
                supplierRepository,
                keywordRepository,
                sourcingCandidateService
        );

        category = Category.builder()
                .id(1L)
                .name("食品")
                .build();
        createActor = AppUser.builder()
                .id(9L)
                .email("buyer@ssds.dev")
                .build();
        when(appUserRepository.findByEmail(createActor.getEmail()))
                .thenReturn(Optional.of(createActor));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(keywordRepository.findAllById(Set.of(10L))).thenReturn(List.of(
                com.example.ssds.infra.entity.TrendKeyword.builder().id(10L).build()
        ));
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
        ProductCreateResponse response = createProduct(createRequest(
                TrackType.A,
                null,
                new BigDecimal("80.00"),
                new BigDecimal("120.00")
        ));

        assertEquals(TrackType.A, response.product().trackType());
        assertEquals(new BigDecimal("0.3333"), response.product().marginRate());
        assertEquals(ProductStatus.EVALUATING, response.product().status());
        verify(productRepository).saveAndFlush(argThat(product ->
                product.getCreatedBy() == createActor
        ));
    }

    @Test
    void createDraftAllowsIncompleteTrackAPricing() {
        ProductCreateRequest request = new ProductCreateRequest(
                "未完成草稿",
                1L,
                null,
                null,
                null,
                null,
                Season.ALL,
                TrackType.A,
                null,
                null,
                null,
                null,
                null,
                Set.of(),
                true
        );

        ProductCreateResponse response = createProduct(request);

        assertEquals(ProductStatus.DRAFT, response.product().status());
        assertNull(response.product().cost());
    }

    @Test
    void submitCompleteDraftMovesToEvaluating() {
        Product product = existingProduct(TrackType.A, null);
        product.setStatus(ProductStatus.DRAFT);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        ProductUpdateResponse response = service.update(
                product.getId(),
                updateRequest(
                        TrackType.A,
                        null,
                        new BigDecimal("80.00"),
                        new BigDecimal("120.00")
                )
        );

        assertEquals(ProductStatus.EVALUATING, response.product().status());
    }

    @Test
    void nonDraftCannotBeSavedBackAsDraft() {
        Product product = existingProduct(TrackType.A, null);
        product.setStatus(ProductStatus.WATCHING);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        ProductUpdateRequest request = new ProductUpdateRequest(
                "修改後商品",
                1L,
                null,
                null,
                null,
                null,
                Season.ALL,
                TrackType.A,
                null,
                null,
                null,
                null,
                null,
                Set.of(),
                true
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.update(product.getId(), request)
        );

        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, exception.getErrorCode());
        verify(productRepository, never()).saveAndFlush(product);
    }

    @Test
    void createWithoutTrackTypeDefaultsToTrackA() {
        ProductCreateResponse response = createProduct(createRequest(
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
                createProduct(createRequest(
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
                createProduct(createRequest(
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
                createProduct(createRequest(
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
        ProductCreateResponse response = createProduct(createRequest(
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
    void createTrackBWithOptionalPricingReturnsPricingForEditing() {
        ProductCreateResponse response = createProduct(createRequest(
                TrackType.B,
                SourcingStatus.SOURCING,
                new BigDecimal("80.00"),
                new BigDecimal("120.00")
        ));

        assertEquals(new BigDecimal("80.00"), response.product().cost());
        assertEquals(new BigDecimal("120.00"), response.product().suggestedPrice());
        assertEquals(new BigDecimal("0.3333"), response.product().marginRate());
    }

    @Test
    void createTrackBWithoutSourcingStatusDefaultsToPending() {
        ProductCreateResponse response = createProduct(createRequest(
                TrackType.B,
                null,
                null,
                null
        ));

        assertEquals(SourcingStatus.PENDING, response.product().sourcingStatus());
    }

    @Test
    void createWithInvalidTemperatureRangeReturnsValidationFailure() {
        ProductCreateRequest request = new ProductCreateRequest(
                "測試商品",
                1L,
                null,
                new BigDecimal("80.00"),
                new BigDecimal("120.00"),
                10,
                Season.ALL,
                TrackType.A,
                null,
                Set.of(LogisticsCondition.NORMAL),
                new BigDecimal("30.0"),
                new BigDecimal("20.0"),
                180,
                Set.of(),
                false
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> createProduct(request)
        );

        assertBadRequest(
                exception,
                "idealTempMin",
                "適溫區間下限不可大於上限"
        );
        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void createWithOnlyTemperatureMinimumReturnsValidationFailure() {
        ProductCreateRequest request = new ProductCreateRequest(
                "測試商品",
                1L,
                null,
                new BigDecimal("80.00"),
                new BigDecimal("120.00"),
                10,
                Season.ALL,
                TrackType.A,
                null,
                Set.of(LogisticsCondition.NORMAL),
                new BigDecimal("20.0"),
                null,
                180,
                Set.of(),
                false
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> createProduct(request)
        );

        assertBadRequest(
                exception,
                "idealTempMax",
                "適溫區間上下限必須同時填寫"
        );
        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void createWithOnlyTemperatureMaximumReturnsValidationFailure() {
        ProductCreateRequest request = new ProductCreateRequest(
                "測試商品",
                1L,
                null,
                new BigDecimal("80.00"),
                new BigDecimal("120.00"),
                10,
                Season.ALL,
                TrackType.A,
                null,
                Set.of(LogisticsCondition.NORMAL),
                null,
                new BigDecimal("26.0"),
                180,
                Set.of(),
                false
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> createProduct(request)
        );

        assertBadRequest(
                exception,
                "idealTempMin",
                "適溫區間上下限必須同時填寫"
        );
        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void createTrackAIgnoresSourcingStatus() {
        ProductCreateResponse response = createProduct(createRequest(
                TrackType.A,
                SourcingStatus.SOURCING,
                new BigDecimal("80.00"),
                new BigDecimal("120.00")
        ));

        assertNull(response.product().sourcingStatus());
    }

    @Test
    void updateWithoutSourcingStatusKeepsExistingTrackBStatus() {
        Product product = existingProduct(TrackType.B, SourcingStatus.SOURCING);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        ProductUpdateResponse response = service.update(
                product.getId(),
                updateRequest(null, null, null, null)
        );

        assertEquals(TrackType.B, response.product().trackType());
        assertEquals(SourcingStatus.SOURCING, response.product().sourcingStatus());
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
    void updateTrackBToAClearsSourcingStatusAndInvalidatesOldScores() {
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
        verify(productScoreRepository).deactivateAllCurrent(product.getId());
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
        verify(productScoreRepository).deactivateAllCurrent(product.getId());
    }

    @Test
    void createEntersEvaluatingWhileRegularUpdateKeepsExistingStatus() {
        ProductCreateResponse created = createProduct(createRequest(
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
        assertEquals(ProductStatus.LISTED, updated.product().status());
    }

    @Test
    void assignCategoryUpdatesAllProductsWithoutChangingTheirStatus() {
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
        verify(sourcingCandidateService, never()).synchronize(first);
        verify(sourcingCandidateService).synchronize(second);
        verify(productScoreRepository).deactivateAllCurrent(first.getId());
        verify(productScoreRepository, never()).deactivateAllCurrent(second.getId());
        assertEquals(targetCategory, second.getCategory());
        assertEquals(ProductStatus.LISTED, first.getStatus());
        assertEquals(ProductStatus.WATCHING, second.getStatus());
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

    @Test
    void deleteMarksProductWithTimestampAndActor() {
        Product product = existingProduct(TrackType.A, null);
        AppUser actor = AppUser.builder()
                .id(2L)
                .email("lead@ssds.dev")
                .build();

        when(productRepository.findById(product.getId()))
                .thenReturn(Optional.of(product));
        when(appUserRepository.findByEmail("lead@ssds.dev"))
                .thenReturn(Optional.of(actor));

        service.delete(product.getId(), "lead@ssds.dev", "127.0.0.1");

        assertEquals(actor, product.getDeletedBy());
        assertTrue(product.getDeletedAt() != null);
        verify(productRepository).saveAndFlush(product);
        verify(auditLogRepository).save(any(AuditLog.class));
    }

    @Test
    void disableBatchSoftDeletesAllProductsAndWritesAudits() {
        Product first = existingProduct(TrackType.A, null);
        first.setId(50L);
        Product second = existingProduct(TrackType.B, SourcingStatus.PENDING);
        second.setId(51L);
        AppUser actor = AppUser.builder()
                .id(2L)
                .email("lead@ssds.dev")
                .build();
        when(productRepository.findAllById(Set.of(50L, 51L)))
                .thenReturn(List.of(first, second));
        when(appUserRepository.findByEmail(actor.getEmail()))
                .thenReturn(Optional.of(actor));

        ProductBatchDisableResponse response = service.disableBatch(
                new ProductBatchDisableRequest(Set.of(50L, 51L)),
                actor.getEmail(),
                "127.0.0.1"
        );

        assertEquals(2, response.disabledCount());
        assertEquals(Set.of(50L, 51L), response.productIds());
        assertEquals(actor, first.getDeletedBy());
        assertEquals(actor, second.getDeletedBy());
        assertTrue(first.getDeletedAt() != null);
        assertTrue(second.getDeletedAt() != null);
        verify(productRepository).saveAllAndFlush(List.of(first, second));
        verify(auditLogRepository).saveAll(argThat(audits ->
                ((List<?>) audits).size() == 2
        ));
    }

    @Test
    void disableBatchWhenAnyProductIsMissingDoesNotModifyAnyProduct() {
        Product found = existingProduct(TrackType.A, null);
        found.setId(50L);
        when(productRepository.findAllById(Set.of(50L, 999L)))
                .thenReturn(List.of(found));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.disableBatch(
                        new ProductBatchDisableRequest(Set.of(50L, 999L)),
                        "lead@ssds.dev",
                        "127.0.0.1"
                )
        );

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("999"));
        assertNull(found.getDeletedAt());
        assertNull(found.getDeletedBy());
        verify(productRepository, never()).saveAllAndFlush(anyList());
        verify(auditLogRepository, never()).saveAll(anyList());
    }

    @Test
    void decisionRoleCanRejectEvaluatingProductWithReason() {
        Product product = existingProduct(TrackType.A, null);
        AppUser actor = mockActor(product);

        ProductStatusUpdateResponse response = service.changeStatus(
                product.getId(),
                new ProductStatusUpdateRequest(
                        ProductStatus.REJECTED,
                        "市場需求不足，暫不導入"
                ),
                actor.getEmail(),
                Set.of("ROLE_BUYER"),
                "127.0.0.1"
        );

        assertEquals(ProductStatus.EVALUATING, response.previousStatus());
        assertEquals(ProductStatus.REJECTED, response.currentStatus());
        assertEquals("市場需求不足，暫不導入", product.getRejectReason());
        verify(auditLogRepository).save(any(AuditLog.class));
    }

    @Test
    void buyerCannotReevaluateRejectedProduct() {
        Product product = existingProduct(TrackType.A, null);
        product.setStatus(ProductStatus.REJECTED);
        AppUser actor = mockActor(product);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.changeStatus(
                        product.getId(),
                        new ProductStatusUpdateRequest(ProductStatus.EVALUATING, null),
                        actor.getEmail(),
                        Set.of("ROLE_BUYER"),
                        "127.0.0.1"
                )
        );

        assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
    }

    @Test
    void editRoleCanMarkAdoptedProductAsListed() {
        Product product = existingProduct(TrackType.A, null);
        product.setStatus(ProductStatus.ADOPTED);
        AppUser actor = mockActor(product);

        ProductStatusUpdateResponse response = service.changeStatus(
                product.getId(),
                new ProductStatusUpdateRequest(ProductStatus.LISTED, null),
                actor.getEmail(),
                Set.of("ROLE_DATA_ADMIN"),
                "127.0.0.1"
        );

        assertEquals(ProductStatus.LISTED, response.currentStatus());
        assertTrue(response.listedAt() != null);
    }

    @Test
    void invalidStatusTransitionReturnsConflictCode() {
        Product product = existingProduct(TrackType.A, null);
        product.setStatus(ProductStatus.LISTED);
        AppUser actor = mockActor(product);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.changeStatus(
                        product.getId(),
                        new ProductStatusUpdateRequest(ProductStatus.EVALUATING, null),
                        actor.getEmail(),
                        Set.of("ROLE_BUYER_LEAD"),
                        "127.0.0.1"
                )
        );

        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, exception.getErrorCode());
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
                Set.of(LogisticsCondition.NORMAL),
                null,
                null,
                180,
                Set.of(10L),
                false
        );
    }

    private ProductCreateResponse createProduct(ProductCreateRequest request) {
        return service.create(request, createActor.getEmail());
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
                Set.of(LogisticsCondition.CHILLED),
                null,
                null,
                120,
                Set.of(10L),
                false
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

    private AppUser mockActor(Product product) {
        AppUser actor = AppUser.builder()
                .id(2L)
                .email("actor@ssds.dev")
                .build();
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(appUserRepository.findByEmail(actor.getEmail())).thenReturn(Optional.of(actor));
        return actor;
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
