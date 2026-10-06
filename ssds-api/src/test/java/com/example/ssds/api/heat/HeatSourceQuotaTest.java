package com.example.ssds.api.heat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.AdapterType;
import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.SourceAvailability;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.ingest.ApifyUsage;
import com.example.ssds.ingest.HeatSourceAdapter;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-14-2：把 Apify 用量寫回 heat_source 額度欄位的規則（單位：美分）。
 */
class HeatSourceQuotaTest {

    private HeatSource source(int used, Integer limit) {
        return HeatSource.builder()
                .id(1L)
                .sourceCode(HeatSourceCode.THREADS)
                .adapterType(AdapterType.REST)
                .quotaUsed(used)
                .quotaLimit(limit)
                .build();
    }

    @Test
    @DisplayName("讀到用量時是覆寫，不是累加")
    void overwritesInsteadOfAccumulating() {
        HeatSource source = source(1000, 5000);
        HeatSourceAdapter adapter = mock(HeatSourceAdapter.class);
        when(adapter.fetchQuota()).thenReturn(Optional.of(new ApifyUsage(4300, 30000)));

        boolean updated = HeatSourceQuota.refresh(source, adapter);

        assertThat(updated).isTrue();
        assertThat(source.getQuotaUsed()).isEqualTo(4300);
        assertThat(source.getQuotaLimit()).isEqualTo(30000);
    }

    @Test
    @DisplayName("Apify 沒有回傳上限時，把舊的上限清成 null")
    void clearsLimitWhenApifyReturnsNone() {
        HeatSource source = source(1000, 5000);
        HeatSourceAdapter adapter = mock(HeatSourceAdapter.class);
        when(adapter.fetchQuota()).thenReturn(Optional.of(new ApifyUsage(700, null)));

        boolean updated = HeatSourceQuota.refresh(source, adapter);

        assertThat(updated).isTrue();
        assertThat(source.getQuotaUsed()).isEqualTo(700);
        assertThat(source.getQuotaLimit()).isNull();
    }

    @Test
    @DisplayName("查不到用量：保留原值並回傳 false")
    void keepsExistingValuesWhenUsageUnavailable() {
        HeatSource source = source(1000, 5000);
        HeatSourceAdapter adapter = mock(HeatSourceAdapter.class);
        when(adapter.fetchQuota()).thenReturn(Optional.empty());

        boolean updated = HeatSourceQuota.refresh(source, adapter);

        assertThat(updated).isFalse();
        assertThat(source.getQuotaUsed()).isEqualTo(1000);
        assertThat(source.getQuotaLimit()).isEqualTo(5000);
    }

    @Test
    @DisplayName("adapter 丟例外：吞掉、保留原值並回傳 false")
    void swallowsAdapterExceptionAndKeepsValues() {
        HeatSource source = source(1000, 5000);
        HeatSourceAdapter adapter = mock(HeatSourceAdapter.class);
        when(adapter.fetchQuota()).thenThrow(new IllegalStateException("boom"));

        boolean updated = HeatSourceQuota.refresh(source, adapter);

        assertThat(updated).isFalse();
        assertThat(source.getQuotaUsed()).isEqualTo(1000);
        assertThat(source.getQuotaLimit()).isEqualTo(5000);
    }

    @Test
    @DisplayName("isExhausted：上限為 null 或 0 視為無上限")
    void isExhaustedTreatsMissingLimitAsUnlimited() {
        assertThat(HeatSourceQuota.isExhausted(source(999_999, null))).isFalse();
        assertThat(HeatSourceQuota.isExhausted(source(999_999, 0))).isFalse();
    }

    @Test
    @DisplayName("isExhausted：用量達到上限才算用完")
    void isExhaustedOnlyWhenUsedReachesLimit() {
        assertThat(HeatSourceQuota.isExhausted(source(4999, 5000))).isFalse();
        assertThat(HeatSourceQuota.isExhausted(source(5000, 5000))).isTrue();
    }

    @Test
    @DisplayName("hasRoom：剛讀到的用量已滿 → 擋下，並把最新用量寫回")
    void hasRoomBlocksWhenFreshUsageIsExhausted() {
        HeatSource source = source(1000, 5000);
        HeatSourceAdapter adapter = mock(HeatSourceAdapter.class);
        when(adapter.fetchQuota()).thenReturn(Optional.of(new ApifyUsage(5000, 5000)));

        assertThat(HeatSourceQuota.hasRoom(source, adapter)).isFalse();
        assertThat(source.getQuotaUsed()).isEqualTo(5000);
    }

    @Test
    @DisplayName("hasRoom：還有額度 → 放行")
    void hasRoomAllowsWhenQuotaLeft() {
        HeatSource source = source(5000, 5000);
        HeatSourceAdapter adapter = mock(HeatSourceAdapter.class);
        when(adapter.fetchQuota()).thenReturn(Optional.of(new ApifyUsage(100, 5000)));

        assertThat(HeatSourceQuota.hasRoom(source, adapter)).isTrue();
    }

    @Test
    @DisplayName("hasRoom：讀不到用量 → 放行（fail-open），即使資料庫舊值看起來已滿")
    void hasRoomFailsOpenWhenUsageUnreadable() {
        HeatSource source = source(5000, 5000);
        HeatSourceAdapter adapter = mock(HeatSourceAdapter.class);
        when(adapter.fetchQuota()).thenReturn(Optional.empty());

        assertThat(HeatSourceQuota.hasRoom(source, adapter)).isTrue();
    }

    @Test
    @DisplayName("applyIngestResult：有資料但額度 ≥80% → DEGRADED（不再被寫回 AVAILABLE）")
    void ingestWithDataButHighQuotaIsDegraded() {
        HeatSource source = source(4000, 5000);

        source.applyIngestResult(true, LocalDate.now());

        assertThat(source.getAvailability()).isEqualTo(SourceAvailability.DEGRADED);
    }

    @Test
    @DisplayName("applyIngestResult：採集後額度剛好 100% → 只降為 DEGRADED，不標 UNAVAILABLE")
    void ingestWithFullQuotaIsOnlyDegraded() {
        HeatSource source = source(5000, 5000);

        source.applyIngestResult(true, LocalDate.now());

        assertThat(source.getAvailability()).isEqualTo(SourceAvailability.DEGRADED);
    }

    @Test
    @DisplayName("applyProbeResult（測試連線）：額度 100% 仍是 UNAVAILABLE，維持規格書定義")
    void probeWithFullQuotaIsStillUnavailable() {
        HeatSource source = source(5000, 5000);

        source.applyProbeResult(true, LocalDate.now());

        assertThat(source.getAvailability()).isEqualTo(SourceAvailability.UNAVAILABLE);
    }

    @Test
    @DisplayName("applyIngestResult：有資料、額度充足 → AVAILABLE，且清掉先前的探測失敗次數")
    void ingestWithDataAndLowQuotaIsAvailableAndResetsFailures() {
        HeatSource source = source(100, 5000);
        source.setConsecutiveProbeFailures((short) 3);

        source.applyIngestResult(true, LocalDate.now());

        assertThat(source.getAvailability()).isEqualTo(SourceAvailability.AVAILABLE);
        assertThat(source.getConsecutiveProbeFailures()).isZero();
    }

    @Test
    @DisplayName("applyIngestResult：沒有資料 → DEGRADED，且不累計探測失敗次數")
    void ingestWithoutDataIsDegradedWithoutCountingFailure() {
        HeatSource source = source(100, 5000);

        source.applyIngestResult(false, LocalDate.now());

        assertThat(source.getAvailability()).isEqualTo(SourceAvailability.DEGRADED);
        assertThat(source.getConsecutiveProbeFailures()).isZero();
    }

    @Test
    @DisplayName("markQuotaExhausted：只改 availability，不動 enabled 與探測欄位")
    void markQuotaExhaustedOnlyTouchesAvailability() {
        HeatSource source = source(5000, 5000);
        source.setConsecutiveProbeFailures((short) 1);

        source.markQuotaExhausted();

        assertThat(source.getAvailability()).isEqualTo(SourceAvailability.UNAVAILABLE);
        assertThat(source.isEnabled()).isTrue();
        assertThat(source.getConsecutiveProbeFailures()).isEqualTo((short) 1);
        assertThat(source.getLastProbedAt()).isNull();
    }

    @Test
    @DisplayName("額度重置後下一次採集成功 → 從 UNAVAILABLE 自動恢復")
    void recoversAfterQuotaReset() {
        HeatSource source = source(0, 5000);
        source.setAvailability(SourceAvailability.UNAVAILABLE);

        source.applyIngestResult(true, LocalDate.now());

        assertThat(source.getAvailability()).isEqualTo(SourceAvailability.AVAILABLE);
    }
}
