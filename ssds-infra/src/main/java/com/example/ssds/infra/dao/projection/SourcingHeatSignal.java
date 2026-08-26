package com.example.ssds.infra.dao.projection;

import com.example.ssds.core.domain.HeatStage;

/** B 軌候選建立時使用的最新熱度階段摘要。 */
public record SourcingHeatSignal(
        Long keywordId,
        HeatStage heatStage,
        short stageWeeks,
        Integer estimatedLifespanDays
) {
}
