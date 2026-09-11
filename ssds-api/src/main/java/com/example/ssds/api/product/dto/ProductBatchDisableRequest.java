package com.example.ssds.api.product.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Set;

/** FR-03-1 批次停用品項的請求。 */
public record ProductBatchDisableRequest(
        @NotEmpty(message = "至少選擇一個品項")
        @Size(max = 100, message = "一次最多停用 100 個品項")
        Set<@NotNull(message = "品項 ID 不可為空")
                @Positive(message = "品項 ID 必須大於 0") Long> productIds
) {
}
