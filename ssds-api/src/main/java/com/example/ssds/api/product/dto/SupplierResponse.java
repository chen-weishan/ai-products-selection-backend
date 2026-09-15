package com.example.ssds.api.product.dto;

/** 供應商清單資料。 */
public record SupplierResponse(
        Long id,
        String name,
        String contact,
        String phone,
        String note
) {
}
