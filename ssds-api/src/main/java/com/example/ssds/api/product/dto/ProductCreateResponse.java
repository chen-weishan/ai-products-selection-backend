package com.example.ssds.api.product.dto;

import java.util.List;

/** 新增品項結果；同類別同名時以 warnings 提示，但不阻擋儲存。 */
public record ProductCreateResponse(
        ProductResponse product,
        List<String> warnings
) {
    public ProductCreateResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
