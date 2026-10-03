package com.example.ssds.api.heat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.heat.dto.HeatSourceTestResponse;
import com.example.ssds.core.domain.AdapterType;
import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.SourceAvailability;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.ManualHeatTagRepository;
import com.example.ssds.ingest.ApifyUsage;
import com.example.ssds.ingest.HeatSourceAdapter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * FR-14-2：「測試連線」順便刷新 Apify 額度，且要「先刷新額度、再判定狀態」。
 * 與 {@link HeatSourceCommandServiceTest} 分開，避免動到既有測試檔。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HeatSourceCommandServiceQuotaTest {

    @Mock
    private HeatSourceRepository heatSourceRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private ManualHeatTagRepository manualHeatTagRepository;

    @Mock
    private HeatSourceAdapter threadsAdapter;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private HeatSourceCommandService service;

    @BeforeEach
    void setUp() {
        service = new HeatSourceCommandService(
                heatSourceRepository,
                auditLogRepository,
                appUserRepository,
                manualHeatTagRepository,
                List.of(threadsAdapter),
                eventPublisher);
        ReflectionTestUtils.setField(service, "probeEnabled", true);
        when(threadsAdapter.sourceCode()).thenReturn(HeatSourceCode.THREADS);
    }

    private HeatSource threadsSource(int used, Integer limit) {
        HeatSource source = HeatSource.builder()
                .id(1L)
                .sourceCode(HeatSourceCode.THREADS)
                .adapterType(AdapterType.REST)
                .compositeWeight(new BigDecimal("0.300"))
                .enabled(true)
                .quotaUsed(used)
                .quotaLimit(limit)
                .build();
        when(heatSourceRepository.findById(1L)).thenReturn(Optional.of(source));
        return source;
    }

    @Test
    @DisplayName("探測成功會把 Apify 用量寫進額度欄位")
    void successfulProbeWritesApifyUsage() {
        HeatSource source = threadsSource(0, null);
        when(threadsAdapter.probe()).thenReturn(true);
        when(threadsAdapter.fetchQuota()).thenReturn(Optional.of(new ApifyUsage(4300, 30000)));

        HeatSourceTestResponse response = service.testConnection(1L);

        assertThat(response.success()).isTrue();
        assertThat(source.getQuotaUsed()).isEqualTo(4300);
        assertThat(source.getQuotaLimit()).isEqualTo(30000);
        assertThat(response.availability()).isEqualTo(SourceAvailability.AVAILABLE.name());
        verify(heatSourceRepository).save(source);
    }

    @Test
    @DisplayName("剛讀到的用量達 80% → DEGRADED（舊值是 0，證明先刷新再判定）")
    void freshUsageAtEightyPercentDegrades() {
        HeatSource source = threadsSource(0, 30000);
        when(threadsAdapter.probe()).thenReturn(true);
        when(threadsAdapter.fetchQuota()).thenReturn(Optional.of(new ApifyUsage(24000, 30000)));

        HeatSourceTestResponse response = service.testConnection(1L);

        assertThat(response.success()).isTrue();
        assertThat(source.getAvailability()).isEqualTo(SourceAvailability.DEGRADED);
        assertThat(response.availability()).isEqualTo(SourceAvailability.DEGRADED.name());
    }

    @Test
    @DisplayName("剛讀到的用量達 100% → UNAVAILABLE（舊值是 0，證明先刷新再判定）")
    void freshUsageAtFullQuotaMakesUnavailable() {
        HeatSource source = threadsSource(0, 30000);
        when(threadsAdapter.probe()).thenReturn(true);
        when(threadsAdapter.fetchQuota()).thenReturn(Optional.of(new ApifyUsage(30000, 30000)));

        HeatSourceTestResponse response = service.testConnection(1L);

        assertThat(response.success()).isTrue();
        assertThat(source.getAvailability()).isEqualTo(SourceAvailability.UNAVAILABLE);
        assertThat(response.availability()).isEqualTo(SourceAvailability.UNAVAILABLE.name());
    }

    @Test
    @DisplayName("查不到用量：保留舊數字，測試連線仍成功")
    void unavailableUsageKeepsOldValuesAndStillSucceeds() {
        HeatSource source = threadsSource(1000, 5000);
        when(threadsAdapter.probe()).thenReturn(true);
        when(threadsAdapter.fetchQuota()).thenReturn(Optional.empty());

        HeatSourceTestResponse response = service.testConnection(1L);

        assertThat(response.success()).isTrue();
        assertThat(source.getQuotaUsed()).isEqualTo(1000);
        assertThat(source.getQuotaLimit()).isEqualTo(5000);
        assertThat(response.availability()).isEqualTo(SourceAvailability.AVAILABLE.name());
    }

    @Test
    @DisplayName("token 驗證失敗：不去讀用量，額度不動")
    void failedProbeDoesNotReadUsage() {
        HeatSource source = threadsSource(1000, 5000);
        when(threadsAdapter.probe()).thenReturn(false);
        when(threadsAdapter.fetchQuota()).thenReturn(Optional.of(new ApifyUsage(4300, 30000)));

        HeatSourceTestResponse response = service.testConnection(1L);

        assertThat(response.success()).isFalse();
        assertThat(source.getQuotaUsed()).isEqualTo(1000);
        assertThat(source.getQuotaLimit()).isEqualTo(5000);
        verify(threadsAdapter, never()).fetchQuota();
    }

    @Test
    @DisplayName("MANUAL 來源不會呼叫 fetchQuota()")
    void manualSourceNeverFetchesQuota() {
        HeatSource manual = HeatSource.builder()
                .id(2L)
                .sourceCode(HeatSourceCode.MANUAL)
                .adapterType(AdapterType.MANUAL)
                .compositeWeight(BigDecimal.ZERO)
                .enabled(true)
                .build();
        when(heatSourceRepository.findById(2L)).thenReturn(Optional.of(manual));
        when(manualHeatTagRepository.existsByObservedAtAfter(any(Instant.class))).thenReturn(true);

        HeatSourceTestResponse response = service.testConnection(2L);

        assertThat(response.success()).isTrue();
        verify(threadsAdapter, never()).fetchQuota();
    }
}