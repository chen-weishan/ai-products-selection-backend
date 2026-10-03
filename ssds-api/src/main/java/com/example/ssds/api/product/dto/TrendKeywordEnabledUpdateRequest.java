package com.example.ssds.api.product.dto;

import jakarta.validation.constraints.NotNull;

/** S-07 關鍵字啟用狀態更新。 */
public record TrendKeywordEnabledUpdateRequest(
        @NotNull Boolean enabled
) {
}
