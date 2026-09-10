package com.example.ssds.api.product.dto;

import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.Season;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

/** FR-03 品項新增／修改後的單筆回應。 */
public record ProductResponse(
        Long id,
        String name,
        Long categoryId,
        String categoryName,
        Long supplierId,
        String supplierName,
        BigDecimal cost,
        BigDecimal suggestedPrice,
        BigDecimal marginRate,
        Integer moq,
        Season season,
        String targetAudience,
        ProductStatus status,
        TrackType trackType,
        SourcingStatus sourcingStatus,
        String logisticsCondition,
        Integer shelfLifeDays,
        Set<Long> keywordIds,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
