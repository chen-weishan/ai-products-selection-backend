package com.example.ssds.api.product.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.FieldError;
import com.example.ssds.api.product.dto.ProductFestivalAffinityItemRequest;
import com.example.ssds.api.product.dto.ProductFestivalAffinityResponse;
import com.example.ssds.api.product.dto.ProductFestivalAffinityUpdateRequest;
import com.example.ssds.infra.entity.FestivalCalendar;
import com.example.ssds.infra.entity.ItemFestivalAffinity;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.FestivalCalendarRepository;
import com.example.ssds.infra.repository.ItemFestivalAffinityRepository;
import com.example.ssds.infra.repository.ProductRepository;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-03 表單使用的品項×節慶關聯度查詢與完整覆蓋更新。 */
@Service
@Transactional
public class ProductFestivalAffinityService {

    private final ProductRepository productRepository;
    private final ItemFestivalAffinityRepository affinityRepository;
    private final FestivalCalendarRepository festivalRepository;

    public ProductFestivalAffinityService(
            ProductRepository productRepository,
            ItemFestivalAffinityRepository affinityRepository,
            FestivalCalendarRepository festivalRepository
    ) {
        this.productRepository = productRepository;
        this.affinityRepository = affinityRepository;
        this.festivalRepository = festivalRepository;
    }

    @Transactional(readOnly = true)
    public List<ProductFestivalAffinityResponse> get(Long productId) {
        findProduct(productId);
        List<ItemFestivalAffinity> affinities =
                affinityRepository.findByProductIdOrderByFestivalCodeAsc(productId);
        return toResponses(affinities);
    }

    public List<ProductFestivalAffinityResponse> replace(
            Long productId,
            ProductFestivalAffinityUpdateRequest request
    ) {
        Product product = findProduct(productId);
        Map<String, ProductFestivalAffinityItemRequest> normalized = new LinkedHashMap<>();
        for (ProductFestivalAffinityItemRequest item : request.affinities()) {
            String code = item.festivalCode().trim().toUpperCase(Locale.ROOT);
            if (normalized.putIfAbsent(code, item) != null) {
                throw validationException("節慶代碼不可重複：" + code);
            }
        }

        Set<String> requestedCodes = new LinkedHashSet<>(normalized.keySet());
        Set<String> existingCodes = requestedCodes.isEmpty()
                ? Set.of()
                : festivalRepository.findByFestivalCodeIn(requestedCodes)
                        .stream()
                        .map(FestivalCalendar::getFestivalCode)
                        .collect(java.util.stream.Collectors.toSet());
        Set<String> missingCodes = new LinkedHashSet<>(requestedCodes);
        missingCodes.removeAll(existingCodes);
        if (!missingCodes.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "找不到指定的節慶代碼：" + missingCodes
            );
        }

        affinityRepository.deleteByProductId(productId);
        affinityRepository.flush();
        List<ItemFestivalAffinity> saved = affinityRepository.saveAllAndFlush(
                normalized.entrySet().stream()
                        .map(entry -> ItemFestivalAffinity.builder()
                                .product(product)
                                .festivalCode(entry.getKey())
                                .affinity(entry.getValue().affinity())
                                .build())
                        .toList()
        );
        return toResponses(saved);
    }

    private List<ProductFestivalAffinityResponse> toResponses(
            List<ItemFestivalAffinity> affinities
    ) {
        Set<String> codes = affinities.stream()
                .map(ItemFestivalAffinity::getFestivalCode)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, String> names = new LinkedHashMap<>();
        if (!codes.isEmpty()) {
            festivalRepository.findByFestivalCodeIn(codes).forEach(festival ->
                    names.putIfAbsent(festival.getFestivalCode(), festival.getFestivalName()));
        }

        return affinities.stream()
                .sorted(java.util.Comparator.comparing(ItemFestivalAffinity::getFestivalCode))
                .map(affinity -> new ProductFestivalAffinityResponse(
                        affinity.getFestivalCode(),
                        names.get(affinity.getFestivalCode()),
                        affinity.getAffinity()
                ))
                .toList();
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "找不到指定的品項：" + productId
                ));
    }

    private BusinessException validationException(String message) {
        return new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "節慶關聯度驗證失敗",
                List.of(new FieldError("affinities", message))
        );
    }
}
