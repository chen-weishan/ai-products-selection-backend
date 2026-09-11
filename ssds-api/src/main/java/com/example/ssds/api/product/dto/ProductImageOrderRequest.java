package com.example.ssds.api.product.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 依陣列順序重新排列品項圖片。 */
public record ProductImageOrderRequest(
        @NotEmpty(message = "圖片順序不可為空")
        @Size(max = 5, message = "品項圖片最多 5 張")
        List<@NotNull(message = "圖片 ID 不可為空")
                @Positive(message = "圖片 ID 必須大於 0") Long> imageIds
) {
}
