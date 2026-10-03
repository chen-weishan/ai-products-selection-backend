package com.example.ssds.api.calibration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.calibration.dto.CalibrationReportResponse;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.calibration.CalibrationSample;
import com.example.ssds.calibration.WeightScheme;
import com.example.ssds.core.domain.CalibrationStatus;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.CalibrationReport;
import com.example.ssds.infra.entity.WeightProfile;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.CalibrationReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

/** FR-15 步驟 1：產生季度報告的守門規則與 JSON 契約。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CalibrationReportServiceTest {

    /** 2026-10-02 10:00 台北。 */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-02T02:00:00Z"), ZoneOffset.UTC);

    @Mock private CalibrationReportRepository reportRepository;
    @Mock private CalibrationDataLoader loader;
    @Mock private CalibrationActors actors;
    @Mock private EntityManager entityManager;
    @Mock private TypedQuery<CalibrationReport> reportQuery;
    @Mock private TypedQuery<WeightVersion> versionQuery;

    private CalibrationReportService service;

    @BeforeEach
    void setUp() {
        service = new CalibrationReportService(reportRepository, loader, actors, entityManager, 200, 10, CLOCK);
        when(entityManager.createQuery(anyString(), org.mockito.ArgumentMatchers.eq(CalibrationReport.class)))
                .thenReturn(reportQuery);
        when(reportQuery.setParameter(anyString(), any())).thenReturn(reportQuery);
        when(reportQuery.setLockMode(any())).thenReturn(reportQuery);
        when(reportQuery.getResultStream()).thenAnswer(inv -> Stream.empty());
        when(entityManager.createQuery(anyString(), org.mockito.ArgumentMatchers.eq(WeightVersion.class)))
                .thenReturn(versionQuery);
        when(versionQuery.setParameter(anyString(), any())).thenReturn(versionQuery);
        when(versionQuery.setMaxResults(org.mockito.ArgumentMatchers.anyInt())).thenReturn(versionQuery);
        when(versionQuery.getResultStream()).thenAnswer(inv -> Stream.empty());

        WeightVersion current = currentVersion();
        when(loader.currentVersion()).thenReturn(current);
        Map<SceneType, WeightScheme.GradeCut> cuts = new EnumMap<>(SceneType.class);
        for (SceneType scene : SceneType.values()) {
            cuts.put(scene, new WeightScheme.GradeCut(85, 70));
        }
        when(loader.thresholdsOf(current)).thenReturn(cuts);
        when(loader.schemeOf("CURRENT", current)).thenReturn(new WeightScheme("CURRENT", "v2", 2L,
                CalibrationDataLoader.toDouble(CalibrationDataLoader.weightsOf(current)), cuts));
        when(loader.samples(any())).thenReturn(samples());
        when(reportRepository.saveAndFlush(any())).thenAnswer(inv -> {
            CalibrationReport report = inv.getArgument(0);
            report.setId(7L);
            return report;
        });
    }

    @Test
    void currentQuarterUsesNowAsCutoffAndWritesBothJsonContracts() throws Exception {
        CalibrationReportResponse response = service.generate("2026Q4");

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(loader).samples(cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(CLOCK.instant());
        assertThat(response.sampleSize()).isEqualTo(12);
        assertThat(response.belowMinSample()).isTrue();
        assertThat(response.status()).isEqualTo(CalibrationStatus.PENDING);

        ArgumentCaptor<CalibrationReport> saved = ArgumentCaptor.forClass(CalibrationReport.class);
        verify(reportRepository).saveAndFlush(saved.capture());
        JsonNode regression = CalibrationReportService.JSON.readTree(saved.getValue().getRegressionResult());
        assertThat(regression.path("factorRows")).hasSize(6);
        assertThat(regression.path("scenes")).hasSize(4);
        assertThat(regression.path("baseVersionId").asLong()).isEqualTo(2L);
        // Agent 7 的 parser 要求 factors[] 的數值皆非 null
        regression.path("factors").forEach(f -> {
            assertThat(f.path("correlation").isNumber()).isTrue();
            assertThat(f.path("pValue").isNumber()).isTrue();
        });
        JsonNode backtest = CalibrationReportService.JSON.readTree(saved.getValue().getBacktestResult());
        assertThat(backtest.path("schemes")).hasSize(3);
        backtest.path("backtests").forEach(b -> assertThat(b.path("scheme").isTextual()).isTrue());
    }

    @Test
    void pastQuarterIsCutAtQuarterEnd() {
        service.generate("2026Q3");

        verify(loader).samples(Instant.parse("2026-09-30T16:00:00Z"));
    }

    @Test
    void futureQuarterIsRejected() {
        assertThatThrownBy(() -> service.generate("2027Q1"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void reviewedReportIsNotRegenerated() {
        CalibrationReport reviewed = CalibrationReport.builder()
                .id(1L).quarter("2026Q3").status(CalibrationStatus.APPROVED).build();
        when(reportQuery.getResultStream()).thenAnswer(inv -> Stream.of(reviewed));

        assertThatThrownBy(() -> service.generate("2026Q3"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        verify(reportRepository, never()).saveAndFlush(any());
        verify(reportQuery).setLockMode(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void regeneratingClearsStaleAiInterpretation() {
        CalibrationReport pending = CalibrationReport.builder()
                .id(1L).quarter("2026Q3").status(CalibrationStatus.PENDING)
                .aiInterpretation("舊解讀").model("m").build();
        when(reportQuery.getResultStream()).thenAnswer(inv -> Stream.of(pending));

        service.generate("2026Q3");

        assertThat(pending.getAiInterpretation()).isNull();
        assertThat(pending.getModel()).isNull();
        assertThat(pending.getAttentionNotes()).isEqualTo("[]");
    }

    @Test
    void concurrentFirstCreationBecomesConflictNot500() {
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("uk_quarter"))
                .when(reportRepository).saveAndFlush(any());

        assertThatThrownBy(() -> service.generate("2026Q4"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void manualGenerationChecksRoleAndAudits() {
        service.generate("2026Q4", "lead@ssds.dev", "ip");

        verify(actors).require(org.mockito.ArgumentMatchers.eq("lead@ssds.dev"), any());
        verify(actors).audit(any(), org.mockito.ArgumentMatchers.eq("GENERATE"), anyString(), anyLong(), any(), any(), any());
    }

    private static WeightVersion currentVersion() {
        WeightVersion version = WeightVersion.builder().id(2L).versionNo("v2").name("現行").profiles(new ArrayList<>()).build();
        String[] weights = {"0.500", "0.100", "0.080", "0.070", "0.150", "0.100"};
        for (SceneType scene : SceneType.values()) {
            for (int i = 0; i < weights.length; i++) {
                version.getProfiles().add(WeightProfile.builder().version(version).sceneType(scene)
                        .factorCode(com.example.ssds.calibration.FactorStatistics.BONUS_FACTORS.get(i))
                        .weight(new BigDecimal(weights[i])).build());
            }
        }
        return version;
    }

    /** 12 筆：TREND 與銷量同序（樣本充足），其餘因子無資料（樣本不足）。 */
    private static List<CalibrationSample> samples() {
        List<CalibrationSample> samples = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            samples.add(new CalibrationSample(i, SceneType.VIRAL,
                    Map.of(FactorCode.TREND, 50.0 + i * 4), 0, 100 + i * 10, i % 2 == 0));
        }
        return samples;
    }
}
