package com.example.ssds.api.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.SourceAvailability;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.CampaignSnapshot;
import com.example.ssds.infra.entity.DecisionRecord;
import com.example.ssds.infra.entity.GradeThreshold;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.repository.GradeThresholdRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.SceneClassificationLogRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** §FR-11-1 決策快照：只存還原不了的三類資訊（§7.2.8）。 */
@ExtendWith(MockitoExtension.class)
class DecisionSnapshotFactoryTest {

    @Mock private HeatSourceRepository heatSourceRepository;
    @Mock private GradeThresholdRepository gradeThresholdRepository;
    @Mock private SceneClassificationLogRepository sceneClassificationLogRepository;

    @InjectMocks private DecisionSnapshotFactory factory;

    private static HeatSource source(HeatSourceCode code, String weight, SourceAvailability availability,
            boolean enabled) {
        return HeatSource.builder()
                .sourceCode(code)
                .compositeWeight(new BigDecimal(weight))
                .availability(availability)
                .enabled(enabled)
                .build();
    }

    @Test
    @DisplayName("§5.3.2：啟用且非 UNAVAILABLE 的來源重新正規化為總和 1；停用來源標 DISABLED")
    void renormalizesContributingSources() {
        List<HeatSource> sources = List.of(
                source(HeatSourceCode.THREADS, "0.350", SourceAvailability.DEGRADED, true),
                source(HeatSourceCode.GOOGLE_TRENDS, "0.300", SourceAvailability.AVAILABLE, true),
                source(HeatSourceCode.INSTAGRAM, "0.150", SourceAvailability.UNAVAILABLE, true),
                source(HeatSourceCode.MANUAL, "0.200", SourceAvailability.AVAILABLE, false));

        Map<String, BigDecimal> weights = DecisionSnapshotFactory.appliedCompositeWeights(sources);
        assertThat(weights.get("THREADS")).isEqualByComparingTo("0.538");
        assertThat(weights.get("GOOGLE_TRENDS")).isEqualByComparingTo("0.462");
        assertThat(weights.get("INSTAGRAM")).isEqualByComparingTo("0");
        assertThat(weights.get("MANUAL")).isEqualByComparingTo("0");

        Map<String, String> availability = DecisionSnapshotFactory.sourceAvailability(sources);
        assertThat(availability).containsEntry("THREADS", "DEGRADED")
                .containsEntry("INSTAGRAM", "UNAVAILABLE")
                .containsEntry("MANUAL", DecisionSnapshotFactory.DISABLED);
    }

    @Test
    @DisplayName("全部來源不可用時權重皆為 0，不做除以 0")
    void allUnavailable() {
        Map<String, BigDecimal> weights = DecisionSnapshotFactory.appliedCompositeWeights(List.of(
                source(HeatSourceCode.THREADS, "0.5", SourceAvailability.UNAVAILABLE, true)));
        assertThat(weights.get("THREADS")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("快照記下當下門檻與覆寫旗標；覆寫以該 period 最新一筆判定為準")
    void capturesThresholdAndOverride() {
        DecisionRecord decision = DecisionFixtures.decision(1L, DecisionType.ADOPT);
        when(heatSourceRepository.findAll()).thenReturn(List.of());
        when(gradeThresholdRepository.findByVersionIdAndSceneType(3L, SceneType.REPLENISHMENT))
                .thenReturn(Optional.of(GradeThreshold.builder()
                        .sceneType(SceneType.REPLENISHMENT)
                        .gradeAMin(new BigDecimal("80"))
                        .gradeBMin(new BigDecimal("65"))
                        .build()));
        SceneClassificationLog latestOverridden = SceneClassificationLog.builder()
                .overriddenBy(AppUser.builder().id(2L).build())
                .build();
        SceneClassificationLog olderAuto = SceneClassificationLog.builder().build();
        when(sceneClassificationLogRepository.findByPeriodAndProductIdInOrderByCreatedAtDesc(anyString(), anyCollection()))
                .thenReturn(List.of(latestOverridden, olderAuto));

        CampaignSnapshot snapshot = factory.create(decision);

        assertThat(snapshot.getDecision()).isSameAs(decision);
        assertThat(snapshot.isSceneOverridden()).isTrue();
        assertThat(snapshot.getAppliedThresholds())
                .contains("\"sceneType\":\"REPLENISHMENT\"")
                .contains("\"gradeAMin\":80")
                .contains("\"gradeBMin\":65");
    }

    @Test
    @DisplayName("覆寫後又重跑自動判定：最新一筆不是覆寫，旗標為 false")
    void latestAutoClassificationWins() {
        DecisionRecord decision = DecisionFixtures.decision(1L, DecisionType.ADOPT);
        when(heatSourceRepository.findAll()).thenReturn(List.of());
        when(gradeThresholdRepository.findByVersionIdAndSceneType(any(), any())).thenReturn(Optional.empty());
        when(sceneClassificationLogRepository.findByPeriodAndProductIdInOrderByCreatedAtDesc(anyString(), anyCollection()))
                .thenReturn(List.of(
                        SceneClassificationLog.builder().build(),
                        SceneClassificationLog.builder().overriddenBy(AppUser.builder().id(2L).build()).build()));

        assertThat(factory.create(decision).isSceneOverridden()).isFalse();
    }
}
