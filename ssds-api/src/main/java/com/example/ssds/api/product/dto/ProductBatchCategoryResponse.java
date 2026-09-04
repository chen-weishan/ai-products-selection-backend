package com.example.ssds.api.product.dto;

import java.util.Set;

/** FR-03-1 批次指定品項類別的結果。 */
public record ProductBatchCategoryResponse(
        Long categoryId,
        String categoryName,
        int updatedCount,
        Set<Long> productIds
) {
    public ProductBatchCategoryResponse {
        productIds = productIds == null ? Set.of() : Set.copyOf(productIds);
    }
}
