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

    @Test
    void failureFromEarlierRoundDoesNotLeakIntoSuccessfulLatestRound() {
        var scoring = mock(PureScoringBatchService.class);
        var heat = mock(RiskHeatAlertService.class);
        var season = mock(RiskSeasonAlertService.class);
        var festival = mock(RiskFestivalAlertService.class);
        var service = new RiskRuleRecalculationService(scoring, heat, season, festival);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                // 第一輪執行中又有新異動：會排入下一輪，且第一輪的失敗不該留到第二輪
                service.onRulesChanged(new RiskRulesChangedEvent("LOW_CONFIDENCE"));
                throw new IllegalStateException("first round failure");
            }
            return null;
        }).when(season).detect(any());

        service.onRulesChanged(new RiskRulesChangedEvent("LOW_CONFIDENCE"));

        assertThat(calls.get()).isEqualTo(2);
        assertThat(service.snapshot().running()).isFalse();
        assertThat(service.snapshot().lastError()).isNull();
    }
}
