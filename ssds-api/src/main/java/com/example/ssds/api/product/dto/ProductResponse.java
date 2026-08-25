package com.example.ssds.api.product.dto;

import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.LogisticsCondition;
import com.example.ssds.core.domain.Season;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.core.domain.TrackType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/** FR-03 品項詳情及新增／修改後的單筆回應。 */
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
        ProductStatus status,
        String rejectReason,
        LocalDate listedAt,
        TrackType trackType,
        SourcingStatus sourcingStatus,
        Set<LogisticsCondition> logisticsConditions,
        BigDecimal idealTempMin,
        BigDecimal idealTempMax,
        Integer shelfLifeDays,
        Integer timeGapDays,
        Set<Long> keywordIds,
        Instant createdAt,
        Instant updatedAt
) {
}
