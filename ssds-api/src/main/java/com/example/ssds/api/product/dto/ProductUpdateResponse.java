package com.example.ssds.api.product.dto;

import java.util.List;

/** 修改品項結果；同類別同名時以 warnings 提示，但不阻擋儲存。 */
public record ProductUpdateResponse(
        ProductResponse product,
        List<String> warnings
) {
    public ProductUpdateResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
