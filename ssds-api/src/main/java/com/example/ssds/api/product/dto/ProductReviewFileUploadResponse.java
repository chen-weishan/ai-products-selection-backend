package com.example.ssds.api.product.dto;

/** 單一品項評論 CSV 補件結果。 */
public record ProductReviewFileUploadResponse(
        String fileName,
        int acceptedRows,
        int insertedCount,
        int duplicateCount,
        long totalReviewCount,
        boolean lowConfidence
) {
}
