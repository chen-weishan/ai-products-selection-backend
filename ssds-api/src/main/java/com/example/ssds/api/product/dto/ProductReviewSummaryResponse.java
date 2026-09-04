package com.example.ssds.api.product.dto;

/** S-04 編輯頁顯示既有評論資料量與 AI 信心度門檻。 */
public record ProductReviewSummaryResponse(
        long totalReviewCount,
        boolean lowConfidence
) {
}
