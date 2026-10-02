package com.example.ssds.api.heat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.heat.dto.HeatSourceDetailResponse;
import com.example.ssds.api.heat.dto.HeatSourceTestResponse;
import com.example.ssds.api.heat.dto.HeatSourceUpdateRequest;
import com.example.ssds.core.domain.AdapterType;
import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.SourceAvailability;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.ManualHeatTagRepository;
import com.example.ssds.ingest.HeatSourceAdapter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * S-16 熱度來源管理服務層規則（規格書 FR-14-2、AC-14-5）。
 *
 * <p>權限本身（僅 SYS_ADMIN 可呼叫）由 {@code HeatSourceController} 的
 * {@code @PreAuthorize} 把關，這裡只驗證資料異動、audit_log 與事件發布是否正確。
 * 「事件之後的合成重算＋重評分」見 {@link HeatCompositionRecalculationServiceTest}；
 * AC-14-4 的合成與權重正規化見 infra 模組的 HeatCompositeCalibrationServiceTest
 * 與 TrendQueryDaoNormalizeWeightsTest。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HeatSourceCommandServiceTest {

    private static final Long CURRENT_USER_ID = 100L;

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
        // probeEnabled 是 @Value 欄位，直接 new 時不會被 Spring 注入而停在 false，
        // 那會讓所有「測試連線」案例都走到「暫停中」的短路分支。
        ReflectionTestUtils.setField(service, "probeEnabled", true);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(CURRENT_USER_ID, null));
        when(appUserRepository.getReferenceById(CURRENT_USER_ID))
                .thenReturn(AppUser.builder().id(CURRENT_USER_ID).build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private HeatSource threadsSource() {
        return HeatSource.builder()
                .id(1L)
                .sourceCode(HeatSourceCode.THREADS)
                .adapterType(AdapterType.REST)
                .compositeWeight(new BigDecimal("0.300"))
                .enabled(true)
                .build();
    }

    @Nested
    @DisplayName("update：調整合成權重／啟用狀態（AC-14-5）")
    class Update {

        @Test
        @DisplayName("只切換啟用狀態不影響合成，不發布事件（避免為省額度開關而全量重評）")
        void togglingEnabledOnlyDoesNotPublishEvent() {
            HeatSource source = threadsSource();
            when(heatSourceRepository.findById(1L)).thenReturn(Optional.of(source));

            service.update(1L, new HeatSourceUpdateRequest(false, null));

            assertThat(source.isEnabled()).isFalse();
            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("同時改 enabled 與權重時，權重有變動仍會發布事件")
        void togglingEnabledWithWeightChangePublishesEvent() {
            HeatSource source = threadsSource();
            when(heatSourceRepository.findById(1L)).thenReturn(Optional.of(source));

            service.update(1L, new HeatSourceUpdateRequest(false, new BigDecimal("0.5")));

            verify(eventPublisher).publishEvent(any(HeatSourceCompositionChangedEvent.class));
        }
        @Test
        @DisplayName("找不到來源時拋出 BusinessException，且不寫 audit_log、不發布事件")
        void throwsWhenSourceNotFound() {
            when(heatSourceRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(99L, new HeatSourceUpdateRequest(true, null)))
                    .isInstanceOf(BusinessException.class);

            verifyNoInteractions(auditLogRepository, eventPublisher);
        }

        @Test
        @DisplayName("權重變動：先存檔與 audit_log，再發布事件（AC-14-5 觸發重新評分）")
        void weightChangePublishesEventAfterPersisting() {
            HeatSource source = threadsSource();
            when(heatSourceRepository.findById(1L)).thenReturn(Optional.of(source));

            service.update(1L, new HeatSourceUpdateRequest(null, new BigDecimal("0.500")));

            InOrder order = inOrder(heatSourceRepository, auditLogRepository, eventPublisher);
            order.verify(heatSourceRepository).save(source);
            order.verify(auditLogRepository).save(any(AuditLog.class));
            ArgumentCaptor<HeatSourceCompositionChangedEvent> captor =
                    ArgumentCaptor.forClass(HeatSourceCompositionChangedEvent.class);
            order.verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().sourceId()).isEqualTo(1L);
            assertThat(captor.getValue().sourceCode()).isEqualTo(HeatSourceCode.THREADS);
        }

        @Test
        @DisplayName("值沒有實際變動（0.3 與 0.300 相等、enabled 不變）不發布事件，避免無意義的全量重評")
        void unchangedValuesDoNotPublishEvent() {
            HeatSource source = threadsSource();
            when(heatSourceRepository.findById(1L)).thenReturn(Optional.of(source));

            service.update(1L, new HeatSourceUpdateRequest(true, new BigDecimal("0.3")));

            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("兩欄皆為 null（不異動）不發布事件")
        void emptyRequestDoesNotPublishEvent() {
            HeatSource source = threadsSource();
            when(heatSourceRepository.findById(1L)).thenReturn(Optional.of(source));

            service.update(1L, new HeatSourceUpdateRequest(null, null));

            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    @DisplayName("testConnection：測試連線（S-16）")
    class TestConnection {

        @Test
        @DisplayName("探測成功時回傳 success=true 並套用與健康檢查相同的判定邏輯")
        void probeSuccessUpdatesAvailability() {
            HeatSource source = threadsSource();
            when(heatSourceRepository.findById(1L)).thenReturn(Optional.of(source));
            when(threadsAdapter.sourceCode()).thenReturn(HeatSourceCode.THREADS);
            when(threadsAdapter.probe()).thenReturn(true);

            HeatSourceTestResponse response = service.testConnection(1L);

            assertThat(response.success()).isTrue();
            assertThat(response.availability()).isEqualTo(SourceAvailability.AVAILABLE.name());
            assertThat(source.getLastProbedAt()).isNotNull();
        }

        @Test
        @DisplayName("第一次探測失敗：success=false、狀態降為 DEGRADED（未達連續 2 次）")
        void firstProbeFailureDegrades() {
            HeatSource source = threadsSource();
            when(heatSourceRepository.findById(1L)).thenReturn(Optional.of(source));
            when(threadsAdapter.sourceCode()).thenReturn(HeatSourceCode.THREADS);
            when(threadsAdapter.probe()).thenReturn(false);

            HeatSourceTestResponse response = service.testConnection(1L);

            assertThat(response.success()).isFalse();
            assertThat(response.availability()).isEqualTo(SourceAvailability.DEGRADED.name());
        }

        @Test
        @DisplayName("連續第 2 次探測失敗：狀態變 UNAVAILABLE")
        void secondConsecutiveFailureMakesUnavailable() {
            HeatSource source = threadsSource();
            source.setConsecutiveProbeFailures((short) 1);
            when(heatSourceRepository.findById(1L)).thenReturn(Optional.of(source));
            when(threadsAdapter.sourceCode()).thenReturn(HeatSourceCode.THREADS);
            when(threadsAdapter.probe()).thenReturn(false);

            HeatSourceTestResponse response = service.testConnection(1L);

            assertThat(response.availability()).isEqualTo(SourceAvailability.UNAVAILABLE.name());
        }

        @Test
        @DisplayName("MANUAL 來源改用「最近 30 日是否有標記」判定，不呼叫任何 adapter")
        void manualSourceProbesViaRecentTagExistence() {
            HeatSource source = manualSource();
            when(heatSourceRepository.findById(2L)).thenReturn(Optional.of(source));
            when(manualHeatTagRepository.existsByObservedAtAfter(any(Instant.class))).thenReturn(true);

            HeatSourceTestResponse response = service.testConnection(2L);

            assertThat(response.success()).isTrue();
            verify(threadsAdapter, never()).probe();
        }

        @Test
        @DisplayName("MANUAL 來源最近 30 日沒有任何標記：success=false")
        void manualSourceWithoutRecentTagsFails() {
            HeatSource source = manualSource();
            when(heatSourceRepository.findById(2L)).thenReturn(Optional.of(source));
            when(manualHeatTagRepository.existsByObservedAtAfter(any(Instant.class))).thenReturn(false);

            assertThat(service.testConnection(2L).success()).isFalse();
        }

        @Test
        @DisplayName("沒有對應 adapter 的來源測試連線時拋出例外")
        void throwsWhenNoAdapterRegisteredForSource() {
            HeatSource source = HeatSource.builder()
                    .id(3L)
                    .sourceCode(HeatSourceCode.INSTAGRAM)
                    .adapterType(AdapterType.REST)
                    .compositeWeight(BigDecimal.ZERO)
                    .enabled(true)
                    .build();
            when(heatSourceRepository.findById(3L)).thenReturn(Optional.of(source));
            when(threadsAdapter.sourceCode()).thenReturn(HeatSourceCode.THREADS);

            assertThatThrownBy(() -> service.testConnection(3L)).isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("探測暫停（ssds.heat-source-probe.enabled=false）：不呼叫 adapter、不改 heat_source")
        void pausedProbeShortCircuits() {
            ReflectionTestUtils.setField(service, "probeEnabled", false);
            HeatSource source = threadsSource();
            when(heatSourceRepository.findById(1L)).thenReturn(Optional.of(source));

            HeatSourceTestResponse response = service.testConnection(1L);

            assertThat(response.success()).isFalse();
            assertThat(response.message()).contains("暫停");
            assertThat(source.getLastProbedAt()).isNull();
            verify(threadsAdapter, never()).probe();
            verify(heatSourceRepository, never()).save(any(HeatSource.class));
        }

        private HeatSource manualSource() {
            return HeatSource.builder()
                    .id(2L)
                    .sourceCode(HeatSourceCode.MANUAL)
                    .adapterType(AdapterType.MANUAL)
                    .compositeWeight(BigDecimal.ZERO)
                    .enabled(true)
                    .build();
        }
    }
}
