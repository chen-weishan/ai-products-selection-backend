package com.example.ssds.api.product.dto;

import com.example.ssds.core.domain.ProductStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** FR-03 品項狀態轉換請求。 */
public record ProductStatusUpdateRequest(
        @NotNull(message = "目標狀態不可為空")
        ProductStatus targetStatus,

        @Size(max = 500, message = "淘汰原因不可超過 500 字")
        String rejectReason
) {
}
