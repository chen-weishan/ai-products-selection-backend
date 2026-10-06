package com.example.ssds.api.sourcing;

import java.util.Collection;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 每日熱度基準合成完成後接續執行的 B 軌純規則重算。 */
@Component
@ConditionalOnProperty(
        name = "ai.sourcing.time-gap-schedule-enabled",
        havingValue = "true")
public class SourcingTimeGapRecalculationJob {
    private final SourcingTimeGapRecalculationService service;

    public SourcingTimeGapRecalculationJob(SourcingTimeGapRecalculationService service) {
        this.service = service;
    }

    public void recalculateAfterDailyHeatComposition() {
        recalculateAfterDailyHeatComposition(List.of());
    }

    public void recalculateAfterDailyHeatComposition(Collection<Long> deferredKeywordIds) {
        service.recalculateAllExceptDrivingKeywords(deferredKeywordIds);
    }
}
