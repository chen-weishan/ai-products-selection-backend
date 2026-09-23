package com.example.ssds.infra.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssds.core.domain.AdapterType;
import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.SourceAvailability;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * §FR-14-2「可用性的判定與寫入」三態判定（AC-14-6）。
 *
 * <p>用 JUnit5 直接測，不需要資料庫（比照本模組既有的 {@code ProductListDaoTest} 慣例，
 * 這裡驗的是 entity 內的純邏輯方法，不是查詢或持久化行為）。
 */
class HeatSourceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 22);

    private HeatSource newSource() {
        return HeatSource.builder()
                .id(1L)
                .sourceCode(HeatSourceCode.THREADS)
                .adapterType(AdapterType.REST)
                .quotaLimit(100)
                .quotaUsed(0)
                .build();
    }

    @Test
    void probeSuccessWithLowQuotaAndFreshDataIsAvailable() {
        HeatSource source = newSource();
        source.setLastFetchedAt(TODAY.atStartOfDay(ZoneId.of("Asia/Taipei")).toInstant());

        source.applyProbeResult(true, TODAY);

        assertEquals(SourceAvailability.AVAILABLE, source.getAvailability());
        assertEquals(0, source.getConsecutiveProbeFailures());
    }

    @Test
    void probeSuccessWithQuotaAtEightyPercentIsDegraded() {
        HeatSource source = newSource();
        source.setQuotaUsed(80); // 80/100 = 80% ≥ 80% 門檻
        source.setLastFetchedAt(TODAY.atStartOfDay(ZoneId.of("Asia/Taipei")).toInstant());

        source.applyProbeResult(true, TODAY);

        assertEquals(SourceAvailability.DEGRADED, source.getAvailability());
    }

    @Test
    void probeSuccessWithStaleDataOlderThanTwoDaysIsDegraded() {
        HeatSource source = newSource();
        source.setLastFetchedAt(
                TODAY.minusDays(3).atStartOfDay(ZoneId.of("Asia/Taipei")).toInstant());

        source.applyProbeResult(true, TODAY);

        assertEquals(SourceAvailability.DEGRADED, source.getAvailability());
    }

    @Test
    void probeSuccessWithDataTwoDaysOldIsStillAvailable() {
        // 規格書：「落後 > 2 日」才算 DEGRADED，剛好 2 日不算
        HeatSource source = newSource();
        source.setLastFetchedAt(
                TODAY.minusDays(2).atStartOfDay(ZoneId.of("Asia/Taipei")).toInstant());

        source.applyProbeResult(true, TODAY);

        assertEquals(SourceAvailability.AVAILABLE, source.getAvailability());
    }

    @Test
    void quotaAtOneHundredPercentIsUnavailableRegardlessOfProbeResult() {
        HeatSource source = newSource();
        source.setQuotaUsed(100);
        source.setLastFetchedAt(TODAY.atStartOfDay(ZoneId.of("Asia/Taipei")).toInstant());

        source.applyProbeResult(true, TODAY);

        assertEquals(SourceAvailability.UNAVAILABLE, source.getAvailability());
    }

    @Test
    void twoConsecutiveFailuresBecomesUnavailable() {
        HeatSource source = newSource();

        source.applyProbeResult(false, TODAY);
        assertEquals(1, source.getConsecutiveProbeFailures());
        assertEquals(SourceAvailability.DEGRADED, source.getAvailability());

        source.applyProbeResult(false, TODAY);
        assertEquals(2, source.getConsecutiveProbeFailures());
        assertEquals(SourceAvailability.UNAVAILABLE, source.getAvailability());
    }

    @Test
    void successAfterFailureResetsConsecutiveFailureCount() {
        HeatSource source = newSource();
        source.setLastFetchedAt(TODAY.atStartOfDay(ZoneId.of("Asia/Taipei")).toInstant());

        source.applyProbeResult(false, TODAY);
        assertEquals(1, source.getConsecutiveProbeFailures());

        source.applyProbeResult(true, TODAY);
        assertEquals(0, source.getConsecutiveProbeFailures());
        assertEquals(SourceAvailability.AVAILABLE, source.getAvailability());
    }

    @Test
    void nullQuotaLimitMeansUnlimitedAndNeverTriggersQuotaConditions() {
        // 人工標記來源沒有額度上限（quotaLimit = null），不該被誤判為 UNAVAILABLE／DEGRADED
        HeatSource source = HeatSource.builder()
                .id(2L)
                .sourceCode(HeatSourceCode.MANUAL)
                .adapterType(AdapterType.MANUAL)
                .quotaLimit(null)
                .quotaUsed(999)
                .build();
        source.setLastFetchedAt(Instant.now());

        source.applyProbeResult(true, TODAY);

        assertEquals(SourceAvailability.AVAILABLE, source.getAvailability());
    }

    @Test
    void contributesToCompositeIsFalseWhenUnavailableOrDisabled() {
        HeatSource unavailable = newSource();
        unavailable.setAvailability(SourceAvailability.UNAVAILABLE);
        assertEquals(false, unavailable.contributesToComposite());

        HeatSource disabled = newSource();
        disabled.setEnabled(false);
        disabled.setAvailability(SourceAvailability.AVAILABLE);
        assertEquals(false, disabled.contributesToComposite());

        HeatSource healthy = newSource();
        healthy.setAvailability(SourceAvailability.AVAILABLE);
        healthy.setEnabled(true);
        assertEquals(true, healthy.contributesToComposite());
    }
}
