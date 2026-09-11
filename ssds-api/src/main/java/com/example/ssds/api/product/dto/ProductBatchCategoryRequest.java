package com.example.ssds.api.product.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Set;

/** FR-03-1 批次指定品項類別的請求。 */
public record ProductBatchCategoryRequest(
        @NotEmpty(message = "至少選擇一個品項")
        Set<@NotNull(message = "品項 ID 不可為空")
                @Positive(message = "品項 ID 必須大於 0") Long> productIds,

        @NotNull(message = "類別不可為空")
        @Positive(message = "類別 ID 必須大於 0")
        Long categoryId
) {
}
