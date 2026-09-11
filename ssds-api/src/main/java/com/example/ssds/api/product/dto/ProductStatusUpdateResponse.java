package com.example.ssds.api.product.dto;

import com.example.ssds.core.domain.ProductStatus;
import java.time.LocalDate;

/** FR-03 品項狀態轉換結果。 */
public record ProductStatusUpdateResponse(
        Long id,
        ProductStatus previousStatus,
        ProductStatus currentStatus,
        String rejectReason,
        LocalDate listedAt
) {
}
