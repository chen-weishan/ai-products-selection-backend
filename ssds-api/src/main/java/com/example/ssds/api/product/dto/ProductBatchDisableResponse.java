package com.example.ssds.api.product.dto;

import java.util.Set;

/** FR-03-1 批次停用品項的結果。 */
public record ProductBatchDisableResponse(
        int disabledCount,
        Set<Long> productIds
) {
    public ProductBatchDisableResponse {
        productIds = productIds == null ? Set.of() : Set.copyOf(productIds);
    }
}
