package com.example.ssds.api.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 供應商主檔新增／完整修改內容。 */
public record SupplierUpsertRequest(
        @NotBlank(message = "供應商名稱不可空白")
        @Size(max = 100, message = "供應商名稱不可超過 100 字")
        String name,

        @Size(max = 50, message = "聯絡人不可超過 50 字")
        String contact,

        @Size(max = 30, message = "電話不可超過 30 字")
        String phone,

        @Size(max = 500, message = "備註不可超過 500 字")
        String note
) {}
