package com.example.ssds.api.calibration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.calibration.dto.ReviewCalibrationRequest;
import com.example.ssds.api.calibration.dto.ReviewCalibrationRequest.Action;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.weight.WeightVersionCommandService;
import com.example.ssds.api.weight.dto.CreateWeightVersionRequest;
import com.example.ssds.api.weight.dto.SceneGroupRequest;
import com.example.ssds.api.weight.dto.WeightVersionDetailResponse;
import com.example.ssds.core.domain.CalibrationStatus;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.WeightVersionStatus;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.CalibrationReport;
import com.example.ssds.infra.entity.GradeThreshold;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.WeightVersionRepository;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** FR-15 審核規則，逐條對應 AC-15-3／15-5／15-6。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CalibrationReviewServiceTest {

    static final String LEAD = "lead@ssds.dev";

    @Mock private EntityManager entityManager;
    @Mock private CalibrationReportService reportService;
    @Mock private CalibrationDataLoader loader;
    @Mock private CalibrationActors actors;
    @Mock private WeightVersionCommandService weightVersionCommandService;
    @Mock private WeightVersionRepository weightVersionRepository;

    private CalibrationReviewService service;
    private final AppUser lead = AppUser.builder().id(2L).email(LEAD).displayName("採購主管").build();

    @BeforeEach
    void setUp() {
        service = new CalibrationReviewService(entityManager, reportService, loader, actors,
                weightVersionCommandService, weightVersionRepository);
        when(actors.require(eq(LEAD), any())).thenReturn(lead);
        when(loader.thresholdRows(3L)).thenReturn(thresholds());
        WeightVersionDetailResponse created = mock(WeightVersionDetailResponse.class);
        when(created.id()).thenReturn(50L);
        when(weightVersionCommandService.create(any())).thenReturn(created);
        when(weightVersionRepository.findById(50L)).thenReturn(Optional.of(
                WeightVersion.builder().id(50L).versionNo("2026Q4-cal").status(WeightVersionStatus.DRAFT).build()));
    }

    @Test
    void partialAcceptsOnlyCheckedFactorsAndScalesTheRest() {
        CalibrationReport report = pending(newFormatRegression());

        service.review(1L, new ReviewCalibrationRequest(Action.PARTIAL, List.of(FactorCode.TREND), "只採熱度"), LEAD, "ip");

        ArgumentCaptor<CreateWeightVersionRequest> captor = ArgumentCaptor.forClass(CreateWeightVersionRequest.class);
        verify(weightVersionCommandService).create(captor.capture());
        CreateWeightVersionRequest request = captor.getValue();
        assertThat(request.versionNo()).isEqualTo("2026Q4-cal");
        assertThat(request.sceneGroups()).hasSize(4);
        SceneGroupRequest viral = request.sceneGroups().stream()
                .filter(g -> g.sceneType() == SceneType.VIRAL).findFirst().orElseThrow();
        // TREND 0.500 → 0.570（採納）；MARGIN 建議 0.050 未採納，與其餘因子一起由 0.500 等比例縮成 0.430
        assertThat(viral.weights().get(FactorCode.TREND)).isEqualByComparingTo("0.570");
        assertThat(viral.weights().get(FactorCode.MARGIN)).isEqualByComparingTo("0.086");
        assertThat(viral.weights().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1.000");
        // 門檻沿用報告的基準版本（AC-15-6：新版本含四榜門檻）
        assertThat(viral.gradeAMin()).isEqualByComparingTo("85");
        assertThat(report.getStatus()).isEqualTo(CalibrationStatus.PARTIAL);
        assertThat(report.getAcceptedItems()).isEqualTo("[\"TREND\"]");
        assertThat(report.getReviewedBy()).isSameAs(lead);
    }

    @Test
    void createdDraftLinksBackToTheReport() {
        pending(newFormatRegression());
        WeightVersion version = weightVersionRepository.findById(50L).orElseThrow();

        service.review(1L, new ReviewCalibrationRequest(Action.APPROVE, null, null), LEAD, "ip");

        assertThat(version.getSourceCalibrationId()).isEqualTo(1L);
        assertThat(version.getCreatedBy()).isSameAs(lead);
        assertThat(version.getStatus()).isEqualTo(WeightVersionStatus.DRAFT);
    }

    @Test
    void rejectNeverCreatesAVersion() {
        CalibrationReport report = pending(newFormatRegression());

        service.review(1L, new ReviewCalibrationRequest(Action.REJECT, null, null), LEAD, "ip");

        verify(weightVersionCommandService, never()).create(any());
        assertThat(report.getStatus()).isEqualTo(CalibrationStatus.REJECTED);
    }

    @Test
    void reviewedReportCannotBeReviewedAgain() {
        CalibrationReport report = pending(newFormatRegression());
        report.setStatus(CalibrationStatus.APPROVED);

        assertThatThrownBy(() -> service.review(1L, new ReviewCalibrationRequest(Action.REJECT, null, null), LEAD, "ip"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void reportIsLockedBeforeItsStatusIsChecked() {
        pending(newFormatRegression());

        service.review(1L, new ReviewCalibrationRequest(Action.REJECT, null, null), LEAD, "ip");

        verify(entityManager).find(CalibrationReport.class, 1L, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void partialRejectsFactorsWithoutSuggestedChange() {
        pending(newFormatRegression());

        assertThatThrownBy(() -> service.review(1L,
                new ReviewCalibrationRequest(Action.PARTIAL, List.of(FactorCode.CLIMATE), null), LEAD, "ip"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(weightVersionCommandService, never()).create(any());
    }

    @Test
    void partialRequiresAtLeastOneFactor() {
        pending(newFormatRegression());

        assertThatThrownBy(() -> service.review(1L,
                new ReviewCalibrationRequest(Action.PARTIAL, new ArrayList<>(java.util.Collections.singletonList(null)), null),
                LEAD, "ip"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void legacyReportWithoutScenesCanOnlyBeRejected() {
        ObjectNode legacy = CalibrationReportService.JSON.createObjectNode();
        legacy.put("method", "pearson");
        legacy.putArray("factors");
        pending(legacy);

        assertThatThrownBy(() -> service.review(1L, new ReviewCalibrationRequest(Action.APPROVE, null, null), LEAD, "ip"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);

        service.review(1L, new ReviewCalibrationRequest(Action.REJECT, null, null), LEAD, "ip");
        verify(weightVersionCommandService, never()).create(any());
    }

    @Test
    void approveWithNoSuggestedChangeIsRefused() {
        ObjectNode unchanged = newFormatRegression();
        unchanged.withArray("scenes").forEach(scene -> scene.withArray("weights").forEach(w ->
                ((ObjectNode) w).put("suggestedWeight", w.get("currentWeight").decimalValue())));
        pending(unchanged);

        assertThatThrownBy(() -> service.review(1L, new ReviewCalibrationRequest(Action.APPROVE, null, null), LEAD, "ip"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void roleCheckRunsBeforeAnythingElse() {
        when(actors.require(eq("buyer@ssds.dev"), any()))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "no"));

        assertThatThrownBy(() -> service.review(1L,
                new ReviewCalibrationRequest(Action.APPROVE, null, null), "buyer@ssds.dev", "ip"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        verify(entityManager, never()).find(any(), any(), any(LockModeType.class));
        verify(actors, never()).audit(any(), anyString(), anyString(), any(), any(), any(), any());
    }

    private CalibrationReport pending(ObjectNode regression) {
        CalibrationReport report = CalibrationReport.builder()
                .id(1L).quarter("2026Q4").sampleSize(8)
                .regressionResult(regression.toString())
                .status(CalibrationStatus.PENDING)
                .build();
        when(entityManager.find(CalibrationReport.class, 1L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(report);
        return report;
    }

    /** VIRAL 榜 TREND、MARGIN 有建議調整；其餘榜與因子維持現行。 */
    static ObjectNode newFormatRegression() {
        ObjectNode root = CalibrationReportService.JSON.createObjectNode();
        root.put("method", "spearman-tilt");
        root.put("baseVersionId", 3L);
        root.put("baseVersionNo", "v2");
        ArrayNode scenes = root.putArray("scenes");
        Map<FactorCode, String[]> viral = Map.of(
                FactorCode.TREND, new String[] {"0.500", "0.570"},
                FactorCode.MARGIN, new String[] {"0.100", "0.050"},
                FactorCode.CVR, new String[] {"0.080", "0.080"},
                FactorCode.PRICE_FIT, new String[] {"0.070", "0.070"},
                FactorCode.FESTIVAL, new String[] {"0.150", "0.130"},
                FactorCode.CLIMATE, new String[] {"0.100", "0.100"});
        for (SceneType sceneType : SceneType.values()) {
            ObjectNode scene = scenes.addObject();
            scene.put("sceneType", sceneType.name());
            ArrayNode weights = scene.putArray("weights");
            for (FactorCode code : com.example.ssds.calibration.FactorStatistics.BONUS_FACTORS) {
                String[] pair = viral.get(code);
                ObjectNode w = weights.addObject();
                w.put("code", code.name());
                w.put("currentWeight", new BigDecimal(pair[0]));
                w.put("suggestedWeight", new BigDecimal(sceneType == SceneType.VIRAL ? pair[1] : pair[0]));
            }
        }
        return root;
    }

    private static List<GradeThreshold> thresholds() {
        List<GradeThreshold> rows = new ArrayList<>();
        for (SceneType scene : SceneType.values()) {
            rows.add(GradeThreshold.builder().sceneType(scene)
                    .gradeAMin(new BigDecimal("85")).gradeBMin(new BigDecimal("70")).build());
        }
        return rows;
    }
}
