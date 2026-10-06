package com.example.ssds.api.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ssds.core.domain.Severity;
import com.example.ssds.infra.repository.RiskAlertRepository;

/** S-11 KPI 卡與「最後偵測」。 */
@ExtendWith(MockitoExtension.class)
class RiskAlertSummaryServiceTest {

    @Mock
    private RiskAlertRepository alerts;

    @Test
    @DisplayName("各嚴重度未處理數、本月已處理數與最後偵測時間")
    void summarizes() {
        when(alerts.countOpenGroupedBySeverity()).thenReturn(List.of(
                new Object[] {Severity.HIGH, 3L},
                new Object[] {Severity.MEDIUM, 6L},
                new Object[] {Severity.LOW, 11L}));
        when(alerts.countHandledSince(any())).thenReturn(24L);
        when(alerts.findLastDetectedAt()).thenReturn(Instant.parse("2026-08-11T19:35:00Z"));

        RiskAlertSummaryService.Summary summary = new RiskAlertSummaryService(alerts)
                .summary(Instant.parse("2026-08-12T01:00:00Z"));

        assertThat(summary.highOpen()).isEqualTo(3);
        assertThat(summary.mediumOpen()).isEqualTo(6);
        assertThat(summary.lowOpen()).isEqualTo(11);
        assertThat(summary.handledThisMonth()).isEqualTo(24);
        assertThat(summary.lastDetectedAt().toString()).isEqualTo("2026-08-12T03:35+08:00");
    }

    @Test
    @DisplayName("某嚴重度沒有未處理示警時為 0；完全沒有示警時最後偵測為 null")
    void emptyIsZeroAndNull() {
        when(alerts.countOpenGroupedBySeverity()).thenReturn(List.<Object[]>of(new Object[] {Severity.HIGH, 2L}));
        when(alerts.countHandledSince(any())).thenReturn(0L);
        when(alerts.findLastDetectedAt()).thenReturn(null);

        RiskAlertSummaryService.Summary summary = new RiskAlertSummaryService(alerts)
                .summary(Instant.parse("2026-08-12T01:00:00Z"));

        assertThat(summary.highOpen()).isEqualTo(2);
        assertThat(summary.mediumOpen()).isZero();
        assertThat(summary.lowOpen()).isZero();
        assertThat(summary.lastDetectedAt()).isNull();
    }

    @Test
    @DisplayName("「本月」以台北時間的月初起算（UTC 月底的晚上已屬台北下個月）")
    void monthStartIsTaipeiTime() {
        when(alerts.countOpenGroupedBySeverity()).thenReturn(List.of());
        when(alerts.countHandledSince(any())).thenReturn(0L);

        new RiskAlertSummaryService(alerts).summary(Instant.parse("2026-08-31T17:00:00Z"));

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(alerts).countHandledSince(since.capture());
        assertThat(since.getValue()).isEqualTo(Instant.parse("2026-08-31T16:00:00Z"));
    }
}
