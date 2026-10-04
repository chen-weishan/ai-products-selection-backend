package com.example.ssds.api.decision;

import com.example.ssds.infra.entity.DecisionRecord;
import com.example.ssds.infra.entity.CampaignSnapshot;
import com.example.ssds.infra.entity.GradeThreshold;
import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.repository.GradeThresholdRepository;
import com.example.ssds.infra.repository.HeatSourceRepository;
import com.example.ssds.infra.repository.SceneClassificationLogRepository;
import com.example.ssds.core.domain.SourceAvailability;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * 建立決策當下產生 {@code campaign_snapshot}（規格書 §FR-11-1「決策快照內容」、§7.2.8）。
 *
 * <p>只記三類之後還原不了的資訊：來源可用性、實際合成權重、當時該榜門檻，
 * 外加情境是否經人工覆寫。JSON 形狀與 dev seed（V903）的既有列一致，
 * 新舊資料可用同一套前端程式顯示。
 */
@Component
@RequiredArgsConstructor
class DecisionSnapshotFactory {

    /** 狀態值：來源在 heat_source 中被停用。SourceAvailability 沒有這個值，停用與不可用是兩回事。 */
    static final String DISABLED = "DISABLED";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HeatSourceRepository heatSourceRepository;
    private final GradeThresholdRepository gradeThresholdRepository;
    private final SceneClassificationLogRepository sceneClassificationLogRepository;

    CampaignSnapshot create(DecisionRecord decision) {
        ProductScore score = decision.getScore();
        List<HeatSource> sources = heatSourceRepository.findAll();
        return CampaignSnapshot.builder()
                .decision(decision)
                .sourceAvailability(write(sourceAvailability(sources)))
                .appliedCompositeWeights(write(appliedCompositeWeights(sources)))
                .appliedThresholds(write(appliedThresholds(score)))
                .sceneOverridden(isSceneOverridden(score))
                .build();
    }

    static Map<String, String> sourceAvailability(List<HeatSource> sources) {
        Map<String, String> result = new LinkedHashMap<>();
        for (HeatSource source : sources) {
            result.put(source.getSourceCode().name(),
                    source.isEnabled() ? source.getAvailability().name() : DISABLED);
        }
        return result;
    }

    /**
     * §5.3.2：啟用且非 UNAVAILABLE 的來源參與合成，其設定權重重新正規化為總和 1；
     * 其餘來源記 0。與 {@link HeatSourceRepository#findContributingSources()} 同一條判準。
     *
     * <p>§7.2.8 定義為「決策當下」實際採用的合成權重，故取建立決策這一刻的來源設定。
     * 限制：若評分計算後、決策前來源設定被改過，此值與該筆評分計算時的權重不同——
     * 每筆評分當時的權重目前沒有落地（heat_composite_daily.applied_weights 是關鍵字×日，非品項級），無從還原。
     * 全部來源都不可用時每一項都是 0（沒有東西可以正規化）。
     */
    static Map<String, BigDecimal> appliedCompositeWeights(List<HeatSource> sources) {
        BigDecimal total = sources.stream()
                .filter(DecisionSnapshotFactory::contributes)
                .map(HeatSource::getCompositeWeight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (HeatSource source : sources) {
            BigDecimal weight = contributes(source) && total.signum() > 0
                    ? source.getCompositeWeight().divide(total, 3, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(3);
            result.put(source.getSourceCode().name(), weight);
        }
        return result;
    }

    private static boolean contributes(HeatSource source) {
        return source.isEnabled() && source.getAvailability() != SourceAvailability.UNAVAILABLE;
    }

    private Map<String, Object> appliedThresholds(ProductScore score) {
        Optional<GradeThreshold> threshold = gradeThresholdRepository.findByVersionIdAndSceneType(
                score.getWeightVersion().getId(), score.getSceneType());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sceneType", score.getSceneType() == null ? null : score.getSceneType().name());
        result.put("gradeAMin", threshold.map(GradeThreshold::getGradeAMin).orElse(null));
        result.put("gradeBMin", threshold.map(GradeThreshold::getGradeBMin).orElse(null));
        return result;
    }

    /**
     * 取該 period 的<b>最新一筆</b>情境判定，不是「曾經覆寫過」——
     * 覆寫後又重跑自動判定的品項，當下生效的是自動判定（與 SceneOverrideLookup 同一條規則）。
     */
    private boolean isSceneOverridden(ProductScore score) {
        return sceneClassificationLogRepository
                .findByPeriodAndProductIdInOrderByCreatedAtDesc(
                        score.getPeriod(), List.of(score.getProduct().getId()))
                .stream()
                .findFirst()
                .map(SceneClassificationLog::isOverridden)
                .orElse(false);
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("決策快照 JSON 序列化失敗", e);
        }
    }
}
