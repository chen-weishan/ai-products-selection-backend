package com.example.ssds.api.weight;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.weight.dto.SceneStatsResponse;
import com.example.ssds.api.weight.dto.SceneStatsResponse.SceneStat;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.SceneClassificationLogRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 權重版本的情境判定統計（規格書 §8.2 {@code scene-stats}、S-09「AI 選組規則卡」）。
 *
 * <p>規格書說「依 {@code weight_version_id} 篩選」，但 §7.2.6 的
 * {@code scene_classification_log} 沒有這個欄位。這裡改以<b>版本生效區間</b>對應：
 * {@code [本版 effective_from, 下一版 effective_from)}，台北日期 00:00 起算。
 * 這是設計決定（2026-10-01 Vincent 選定），不是規格明寫。
 */
@Service
@RequiredArgsConstructor
public class WeightVersionSceneStatsService {

    /** effective_from 是台北日期；created_at 存 UTC，比較前要先換成同一個時間點。 */
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    /** 與 FR-11 準確度的比率同一精度（{@code DecisionAccuracyService.RATE_SCALE}）。 */
    private static final int RATE_SCALE = 4;

    private final WeightVersionRepository weightVersionRepository;
    private final SceneClassificationLogRepository sceneClassificationLogRepository;

    @Transactional(readOnly = true)
    public SceneStatsResponse getSceneStats(Long versionId) {
        WeightVersion version = weightVersionRepository.findById(versionId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到權重版本 id=" + versionId));

        LocalDate from = version.getEffectiveFrom();
        if (from == null) {
            // 草稿從未生效，沒有區間可對應；回全 0 而不是 404，畫面照常顯示四列
            return summarize(versionId, null, null, List.of());
        }
        LocalDate to = weightVersionRepository
                .findFirstByEffectiveFromGreaterThanOrderByEffectiveFromAsc(from)
                .map(WeightVersion::getEffectiveFrom)
                .orElse(null);

        Instant fromInstant = from.atStartOfDay(TAIPEI).toInstant();
        List<SceneClassificationLog> logs = to == null
                ? sceneClassificationLogRepository
                        .findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(fromInstant)
                : sceneClassificationLogRepository
                        .findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                                fromInstant, to.atStartOfDay(TAIPEI).toInstant());

        return summarize(versionId, from, to, latestPerProduct(logs));
    }

    /** 查詢已依 createdAt 遞減，每個品項第一次出現的那筆就是最新的。 */
    private static Collection<SceneClassificationLog> latestPerProduct(List<SceneClassificationLog> logs) {
        Map<Long, SceneClassificationLog> latest = new LinkedHashMap<>();
        for (SceneClassificationLog log : logs) {
            latest.putIfAbsent(log.getProduct().getId(), log);
        }
        return latest.values();
    }

    private static SceneStatsResponse summarize(
            Long versionId, LocalDate from, LocalDate to, Collection<SceneClassificationLog> latest) {
        long overridden = latest.stream().filter(SceneClassificationLog::isOverridden).count();
        long aiFailed = latest.stream().filter(l -> l.getAiSceneType() == null).count();

        List<SceneStat> scenes = new ArrayList<>();
        for (SceneType scene : SceneType.values()) {
            long aiJudged = latest.stream().filter(l -> l.getAiSceneType() == scene).count();
            long sceneOverridden = latest.stream()
                    .filter(l -> l.getAiSceneType() == scene && l.isOverridden())
                    .count();
            long finalCount = latest.stream().filter(l -> l.getFinalSceneType() == scene).count();
            scenes.add(new SceneStat(scene, aiJudged, sceneOverridden,
                    rate(sceneOverridden, aiJudged), finalCount));
        }
        return new SceneStatsResponse(versionId, from, to, latest.size(), overridden,
                rate(overridden, latest.size()), aiFailed, scenes);
    }

    private static BigDecimal rate(long numerator, long denominator) {
        if (denominator == 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP);
    }
}
