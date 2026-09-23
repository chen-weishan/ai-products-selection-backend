package com.example.ssds.api.heat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * S-16 熱度來源管理服務層規則（規格書 FR-14-2、AC-14-4、AC-14-5）。
 *
 * <p>權限本身（僅 SYS_ADMIN 可呼叫）由 {@code HeatSourceController} 的
 * {@code @PreAuthorize} 把關，這裡只驗證資料異動與 audit_log 的內容是否正確。
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

    private HeatSourceCommandService service;

    @BeforeEach
    void setUp() {
        service = new HeatSourceCommandService(
                heatSourceRepository,
                auditLogRepository,
                appUserRepository,
                manualHeatTagRepository,
                List.of(threadsAdapter));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(CURRENT_USER_ID, null));
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
        @DisplayName("調整合成權重會寫入新值並留下 audit_log")
        void updatesCompositeWeightAndWritesAuditLog() {
            HeatSource source = threadsSource();
            when(heatSourceRepository.findById(1L)).thenReturn(Optional.of(source));
            when(appUserRepository.getReferenceById(CURRENT_USER_ID))
                    .thenReturn(AppUser.builder().id(CURRENT_USER_ID).build());

            HeatSourceUpdateRequest request = new HeatSourceUpdateRequest(null, new BigDecimal("0.500"));
            HeatSourceDetailResponse response = service.update(1L, request);

            assertThat(response.compositeWeight()).isEqualByComparingTo("0.500");
            assertThat(source.getCompositeWeight()).isEqualByComparingTo("0.500");
            assertThat(source.isEnabled()).isTrue(); // enabled 未帶值，維持原樣

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(captor.capture());
            assertThat(captor.getValue().getEntityType()).isEqualTo("HeatSource");
            assertThat(captor.getValue().getEntityId()).isEqualTo(1L);
            assertThat(captor.getValue().getBeforeJson()).contains("0.300");
            assertThat(captor.getValue().getAfterJson()).contains("0.500");
        }

        @Test
        @DisplayName("停用來源時只改 enabled，權重維持原值")
        void disablingSourceKeepsExistingWeight() {
            HeatSource source = threadsSource();
            when(heatSourceRepository.findById(1L)).thenReturn(Optional.of(source));
            when(appUserRepository.getReferenceById(CURRENT_USER_ID))
                    .thenReturn(AppUser.builder().id(CURRENT_USER_ID).build());

            HeatSourceUpdateRequest request = new HeatSourceUpdateRequest(false, null);
            HeatSourceDetailResponse response = service.update(1L, request);

            assertThat(response.enabled()).isFalse();
            assertThat(response.compositeWeight()).isEqualByComparingTo("0.300");
        }

        @Test
        @DisplayName("找不到來源時拋出 RESOURCE_NOT_FOUND")
        void throwsWhenSourceNotFound() {
            when(heatSourceRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(99L, new HeatSourceUpdateRequest(true, null)))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("testConnection：測試連線（S-16）")
    class TestConnection {

        @Test
        @DisplayName("探測成功時回傳 success=true 並套用與健康檢查排程相同的判定邏輯")
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
        @DisplayName("探測失敗時回傳 success=false")
        void probeFailureReportsFailure() {
            HeatSource source = threadsSource();
            when(heatSourceRepository.findById(1L)).thenReturn(Optional.of(source));
            when(threadsAdapter.sourceCode()).thenReturn(HeatSourceCode.THREADS);
            when(threadsAdapter.probe()).thenReturn(false);

            HeatSourceTestResponse response = service.testConnection(1L);

            assertThat(response.success()).isFalse();
        }

        @Test
        @DisplayName("MANUAL 來源改用「最近 30 日是否有標記」判定，不呼叫任何 adapter")
        void manualSourceProbesViaRecentTagExistence() {
            HeatSource source = HeatSource.builder()
                    .id(2L)
                    .sourceCode(HeatSourceCode.MANUAL)
                    .adapterType(AdapterType.MANUAL)
                    .compositeWeight(BigDecimal.ZERO)
                    .enabled(true)
                    .build();
            when(heatSourceRepository.findById(2L)).thenReturn(Optional.of(source));
            when(manualHeatTagRepository.existsByObservedAtAfter(any(Instant.class))).thenReturn(true);

            HeatSourceTestResponse response = service.testConnection(2L);

            assertThat(response.success()).isTrue();
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
    }
}
