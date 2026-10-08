package com.example.ssds.api.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import com.example.ssds.api.risk.RiskHeatAlertService;
import com.example.ssds.infra.repository.HeatCompositeDailyRepository;

/** §FR-10-2／§5.10：06:30、台北時區、可由 SCHEDULE_HEAT_ALERT 覆寫，且偵測以營業日為準。 */
class RiskHeatAlertJobTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 5);
    private static final Instant NOW = Instant.parse("2026-10-04T22:30:00Z");

    private final RiskHeatAlertService service = mock(RiskHeatAlertService.class);
    private final HeatCompositeDailyRepository composites = mock(HeatCompositeDailyRepository.class);
    private final RiskHeatAlertJob job = new RiskHeatAlertJob(service, composites);

    @Test
    @DisplayName("排程由 S-14 動態管理，不在工作類別硬編碼")
    void scheduleIsManagedByRuntimeSettings() throws Exception {
        Scheduled scheduled = RiskHeatAlertJob.class.getMethod("run").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNull();
    }

    @Test
    @DisplayName("以營業日與偵測時刻呼叫 service，回傳其結果")
    void delegatesToService() {
        RiskHeatAlertService.Result expected = new RiskHeatAlertService.Result(10, 2, 2, 0, 0, 0, 0, 0);
        when(composites.findEnabledKeywordIdsMissingStatDate(DAY)).thenReturn(List.of());
        when(service.detect(DAY, NOW)).thenReturn(expected);

        assertThat(job.runFor(DAY, NOW)).isEqualTo(expected);
        verify(service).detect(DAY, NOW);
    }

    @Test
    @DisplayName("06:00 合成有缺漏時仍執行偵測（警告，不中止）")
    void stillRunsWhenCompositeIsIncomplete() {
        RiskHeatAlertService.Result expected = new RiskHeatAlertService.Result(8, 1, 1, 0, 0, 0, 0, 0);
        when(composites.findEnabledKeywordIdsMissingStatDate(DAY)).thenReturn(List.of(7L, 9L));
        when(service.detect(DAY, NOW)).thenReturn(expected);

        assertThat(job.runFor(DAY, NOW)).isEqualTo(expected);
    }
}
