package com.example.ssds.api.product.dto;

import java.time.Instant;

/** 趨勢關鍵字清單資料。 */
public record TrendKeywordResponse(
        Long id,
        String keyword,
        String geo,
        boolean enabled,
        Instant lastFetchedAt
) {
}
