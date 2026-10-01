package com.example.ssds.api.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.aitask.service.AiTaskService;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.product.dto.ProductFestivalAffinityItemRequest;
import com.example.ssds.api.product.dto.ProductFestivalAffinityResponse;
import com.example.ssds.api.product.dto.ProductFestivalAffinityUpdateRequest;
import com.example.ssds.infra.entity.FestivalCalendar;
import com.example.ssds.infra.entity.ItemFestivalAffinity;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.FestivalCalendarRepository;
import com.example.ssds.infra.repository.ItemFestivalAffinityRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.TrackType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductFestivalAffinityServiceTest {

    private ProductRepository productRepository;
    private ItemFestivalAffinityRepository affinityRepository;
    private FestivalCalendarRepository festivalRepository;
    private AiTaskService aiTaskService;
    private ProductScoreRepository productScoreRepository;
    private ProductFestivalAffinityService service;
    private Product product;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        affinityRepository = mock(ItemFestivalAffinityRepository.class);
        festivalRepository = mock(FestivalCalendarRepository.class);
        aiTaskService = mock(AiTaskService.class);
        productScoreRepository = mock(ProductScoreRepository.class);
        service = new ProductFestivalAffinityService(
                productRepository,
                affinityRepository,
                festivalRepository,
                aiTaskService,
                productScoreRepository
        );
        product = Product.builder().id(50L).build();
        when(productRepository.findById(50L)).thenReturn(Optional.of(product));
        when(productRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(product));
        when(affinityRepository.saveAllAndFlush(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void changedAffinitiesInvalidateScoreAndQueueEligibleProduct() {
        product = Product.builder()
                .id(50L)
                .trackType(TrackType.A)
                .status(ProductStatus.EVALUATING)
                .build();
        when(productRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(product));
        when(festivalRepository.findByFestivalCodeIn(Set.of("MID_AUTUMN")))
                .thenReturn(List.of(festival("MID_AUTUMN", "中秋節")));
        when(affinityRepository.findByProductIdOrderByFestivalCodeAsc(50L))
                .thenReturn(List.of());

        service.replace(
                50L,
                new ProductFestivalAffinityUpdateRequest(List.of(
                        new ProductFestivalAffinityItemRequest(
                                "MID_AUTUMN", new BigDecimal("0.80")))));

        verify(productScoreRepository).deactivateAllCurrent(50L);
        verify(aiTaskService).enqueueFullAnalysis(List.of(product), null, false);
    }

    @Test
    void deferredAffinityChangeInvalidatesScoreWithoutQueuingEarly() {
        product = Product.builder()
                .id(50L)
                .trackType(TrackType.A)
                .status(ProductStatus.EVALUATING)
                .build();
        when(productRepository.findByIdForUpdate(50L)).thenReturn(Optional.of(product));
        when(festivalRepository.findByFestivalCodeIn(Set.of("MID_AUTUMN")))
                .thenReturn(List.of(festival("MID_AUTUMN", "中秋節")));
        when(affinityRepository.findByProductIdOrderByFestivalCodeAsc(50L))
                .thenReturn(List.of());

        service.replace(
                50L,
                new ProductFestivalAffinityUpdateRequest(List.of(
                        new ProductFestivalAffinityItemRequest(
                                "MID_AUTUMN", new BigDecimal("0.80")))),
                true);

        verify(productScoreRepository).deactivateAllCurrent(50L);
        verify(aiTaskService, never()).enqueueFullAnalysis(anyList(), any(), anyBoolean());
    }

    @Test
    void replaceValidAffinitiesUsesNormalizedCodes() {
        when(festivalRepository.findByFestivalCodeIn(
                Set.of("MID_AUTUMN", "LUNAR_NEW_YEAR")))
                .thenReturn(List.of(
                        festival("MID_AUTUMN", "中秋節"),
                        festival("LUNAR_NEW_YEAR", "農曆新年")
                ));

        List<ProductFestivalAffinityResponse> response = service.replace(
                50L,
                new ProductFestivalAffinityUpdateRequest(List.of(
                        new ProductFestivalAffinityItemRequest(
                                "mid_autumn", new BigDecimal("0.80")),
                        new ProductFestivalAffinityItemRequest(
                                "LUNAR_NEW_YEAR", new BigDecimal("0.60"))
                ))
        );

        verify(affinityRepository).deleteByProductId(50L);
        assertEquals(2, response.size());
        assertEquals("LUNAR_NEW_YEAR", response.getFirst().festivalCode());
        assertEquals("農曆新年", response.getFirst().festivalName());
        assertEquals("MID_AUTUMN", response.get(1).festivalCode());
    }

    @Test
    void replaceRejectsDuplicateCodesIgnoringCase() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.replace(
                        50L,
                        new ProductFestivalAffinityUpdateRequest(List.of(
                                new ProductFestivalAffinityItemRequest(
                                        "MID_AUTUMN", new BigDecimal("0.80")),
                                new ProductFestivalAffinityItemRequest(
                                        "mid_autumn", new BigDecimal("0.70"))
                        ))
                )
        );

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
        verify(affinityRepository, never()).deleteByProductId(50L);
    }

    @Test
    void replaceRejectsUnknownFestivalCode() {
        when(festivalRepository.findByFestivalCodeIn(Set.of("UNKNOWN")))
                .thenReturn(List.of());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.replace(
                        50L,
                        new ProductFestivalAffinityUpdateRequest(List.of(
                                new ProductFestivalAffinityItemRequest(
                                        "UNKNOWN", new BigDecimal("0.50"))
                        ))
                )
        );

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.getErrorCode());
        verify(affinityRepository, never()).deleteByProductId(50L);
    }

    @Test
    void replaceChangedAffinitiesIsRejectedWhileAnalysisIsRunning() {
        when(festivalRepository.findByFestivalCodeIn(Set.of("MID_AUTUMN")))
                .thenReturn(List.of(festival("MID_AUTUMN", "中秋節")));
        when(affinityRepository.findByProductIdOrderByFestivalCodeAsc(50L))
                .thenReturn(List.of());
        doThrow(new BusinessException(
                ErrorCode.PRODUCT_ANALYSIS_IN_PROGRESS,
                "品項正在評分"))
                .when(aiTaskService)
                .assertFullAnalysisInputsEditable(50L);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.replace(
                        50L,
                        new ProductFestivalAffinityUpdateRequest(List.of(
                                new ProductFestivalAffinityItemRequest(
                                        "MID_AUTUMN", new BigDecimal("0.80"))))));

        assertEquals(ErrorCode.PRODUCT_ANALYSIS_IN_PROGRESS, exception.getErrorCode());
        verify(affinityRepository, never()).deleteByProductId(50L);
        verify(affinityRepository, never()).saveAllAndFlush(anyList());
    }

    private FestivalCalendar festival(String code, String name) {
        return FestivalCalendar.builder()
                .festivalCode(code)
                .festivalName(name)
                .build();
    }
}
