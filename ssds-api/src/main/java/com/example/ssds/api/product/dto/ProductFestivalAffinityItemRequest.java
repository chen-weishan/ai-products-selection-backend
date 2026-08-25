package com.example.ssds.api.product.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 單一節慶與品項的關聯度。 */
public record ProductFestivalAffinityItemRequest(
        @NotBlank(message = "節慶代碼不可空白")
        @Size(max = 24, message = "節慶代碼不可超過 24 字")
        String festivalCode,

        @NotNull(message = "節慶關聯度不可為空")
        @DecimalMin(value = "0.00", message = "節慶關聯度不可小於 0")
        @DecimalMax(value = "1.00", message = "節慶關聯度不可大於 1")
        @Digits(integer = 1, fraction = 2, message = "節慶關聯度最多 2 位小數")
        BigDecimal affinity
) {
}
