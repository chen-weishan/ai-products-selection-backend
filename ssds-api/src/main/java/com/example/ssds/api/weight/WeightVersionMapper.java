package com.example.ssds.api.weight;

import com.example.ssds.api.weight.dto.SceneWeightGroupResponse;
import com.example.ssds.api.weight.dto.WeightVersionDetailResponse;
import com.example.ssds.api.weight.dto.WeightVersionSummaryResponse;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.GradeThreshold;
import com.example.ssds.infra.entity.WeightProfile;
import com.example.ssds.infra.entity.WeightVersion;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * entity → 回應 DTO 的轉換。純函式、無狀態，因此是 static 而非 Spring bean。
 *
 * <p>放在 ssds-api 而非 ssds-infra：DTO 是 API 契約的一部分，
 * 讓 infra 認識 DTO 會反轉依賴方向（§3.3）。
 */
public final class WeightVersionMapper {

    /** §8.1：回應時間一律以 +08:00 呈現。 */
    private static final ZoneId API_ZONE = ZoneId.of("Asia/Taipei");

    private WeightVersionMapper() {
        throw new AssertionError("工具類別，不應被實例化");
    }

    public static WeightVersionDetailResponse toDetail(
            WeightVersion version, List<GradeThreshold> thresholds) {

        // 先把四榜門檻整理成可查表的形式，避免在下面的迴圈裡重複線性搜尋
        Map<SceneType, GradeThreshold> thresholdByScene = thresholds.stream()
                .collect(Collectors.toMap(
                        GradeThreshold::getSceneType,
                        t -> t,
                        (a, b) -> a,
                        () -> new EnumMap<>(SceneType.class)));

        Map<SceneType, List<WeightProfile>> profilesByScene = version.getProfiles().stream()
                .collect(Collectors.groupingBy(
                        WeightProfile::getSceneType,
                        () -> new EnumMap<>(SceneType.class),
                        Collectors.toList()));

        // 以 SceneType.values() 驅動，而不是以查到的資料驅動：
        // 資料缺一榜時，回應仍會出現那一榜（權重空、門檻 null），前端看得見缺口。
        // 若改用 profilesByScene.keySet()，缺的那榜會安靜地消失。
        List<SceneWeightGroupResponse> groups = Arrays.stream(SceneType.values())
                .map(scene -> toGroup(
                        scene,
                        profilesByScene.getOrDefault(scene, List.of()),
                        thresholdByScene.get(scene)))
                .toList();

        return new WeightVersionDetailResponse(
                version.getId(),
                version.getVersionNo(),
                version.getName(),
                version.getStatus(),
                version.isCurrent(),
                version.getEffectiveFrom(),
                version.getChangeNote(),
                toOffset(version.getCreatedAt()),
                toOffset(version.getApprovedAt()),
                groups);
    }

    private static SceneWeightGroupResponse toGroup(
            SceneType scene, List<WeightProfile> profiles, GradeThreshold threshold) {

        Map<FactorCode, BigDecimal> weights = profiles.stream()
                .collect(Collectors.toMap(
                        WeightProfile::getFactorCode,
                        WeightProfile::getWeight,
                        (a, b) -> a,
                        () -> new EnumMap<>(FactorCode.class)));

        BigDecimal sum = weights.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new SceneWeightGroupResponse(
                scene,
                weights,
                sum,
                threshold == null ? null : threshold.getGradeAMin(),
                threshold == null ? null : threshold.getGradeBMin());
    }

    public static WeightVersionSummaryResponse toSummary(WeightVersion version) {
        return new WeightVersionSummaryResponse(
                version.getId(),
                version.getVersionNo(),
                version.getName(),
                version.getStatus(),
                version.isCurrent(),
                version.getEffectiveFrom(),
                version.getChangeNote(),
                toOffset(version.getCreatedAt()),
                toOffset(version.getApprovedAt())
        );
    }
    
    /** entity 存 Instant（絕對時刻，無時區），API 回 OffsetDateTime（帶 +08:00）。 */
    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : instant.atZone(API_ZONE).toOffsetDateTime();
    }
}
