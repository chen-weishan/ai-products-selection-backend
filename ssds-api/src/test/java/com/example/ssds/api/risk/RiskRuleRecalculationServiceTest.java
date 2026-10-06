package com.example.ssds.api.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.ssds.api.scoring.PureScoringBatchService;
import org.junit.jupiter.api.Test;

class RiskRuleRecalculationServiceTest {
    @Test
    void reportsScanFailureAndStillRunsRemainingScans() {
        var scoring = mock(PureScoringBatchService.class);
        var heat = mock(RiskHeatAlertService.class);
        var season = mock(RiskSeasonAlertService.class);
        var festival = mock(RiskFestivalAlertService.class);
        var service = new RiskRuleRecalculationService(scoring, heat, season, festival);
        doThrow(new IllegalStateException("test failure")).when(season).detect(any());

        service.onRulesChanged(new RiskRulesChangedEvent("LOW_CONFIDENCE"));

        assertThat(service.snapshot().running()).isFalse();
        assertThat(service.snapshot().lastError()).contains("IllegalStateException");
        verify(festival).detect(any(), any());
        reset(season);
        service.onRulesChanged(new RiskRulesChangedEvent("LOW_CONFIDENCE"));
        assertThat(service.snapshot().lastError()).isNull();
    }
}
