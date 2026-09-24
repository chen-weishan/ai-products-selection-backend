package com.example.ssds.api.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 品類主檔新增／完整修改內容。 */
public record CategoryUpsertRequest(
        @NotBlank(message = "類別名稱不可空白")
        @Size(max = 50, message = "類別名稱不可超過 50 字")
        String name,

        @Positive(message = "上層類別 ID 必須大於 0")
        Long parentId,

        @PositiveOrZero(message = "排序不可小於 0")
        int sortOrder
) {}
