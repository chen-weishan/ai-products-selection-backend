package com.example.ssds.ai.model;

import com.example.ssds.core.domain.Season;
import com.example.ssds.core.domain.HeatStage;
import java.math.BigDecimal;
import java.util.List;

/** 只含允許外送的欄位；刻意沒有成本、售價、毛利、供應商或銷量。 */
public record SceneClassifierInput(
        Long productId,
        String productName,
        Long categoryId,
        String categoryName,
        Season season,
        BigDecimal heatSlope7d,
        BigDecimal heatSlope30d,
        BigDecimal heatSlopePercentile,
        HeatStage heatStage,
        HeatBucket heatBucket,
        long historicalCampaignCount,
        List<FestivalMatch> festivalMatches
) {
    public SceneClassifierInput {
        festivalMatches = festivalMatches == null ? List.of() : List.copyOf(festivalMatches);
    }
}
