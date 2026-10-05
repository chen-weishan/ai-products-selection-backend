package com.example.ssds.api.weight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.weight.dto.SceneStatsResponse;
import com.example.ssds.api.weight.dto.SceneStatsResponse.SceneStat;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.entity.WeightVersion;
import com.example.ssds.infra.repository.SceneClassificationLogRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;

/**
 * FR-08 {@code scene-stats}（規格書 §8.2，v3.0 補入）。純 Mockito，不碰資料庫。
 */
@ExtendWith(MockitoExtension.class)
class WeightVersionSceneStatsServiceTest {

    /** 2026-06-24 00:00 台北＝2026-06-23 16:00 UTC。 */
    private static final LocalDate V2_FROM = LocalDate.of(2026, 6, 24);
    private static final Instant V2_FROM_UTC = Instant.parse("2026-06-23T16:00:00Z");

    @Mock
    private WeightVersionRepository weightVersionRepository;

    @Mock
    private SceneClassificationLogRepository sceneClassificationLogRepository;

    @InjectMocks
    private WeightVersionSceneStatsService service;

    @Test
    @DisplayName("版本不存在 → RESOURCE_NOT_FOUND")
    void missingVersionIsNotFound() {
        when(weightVersionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSceneStats(99L))
                .isInstanceOf(BusinessException.class)
                .extracting(t -> ((BusinessException) t).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("草稿沒有生效日 → 全 0、四列照列、比率為 null，且不查判定紀錄")
    void draftReturnsZeros() {
        when(weightVersionRepository.findById(3L))
                .thenReturn(Optional.of(WeightVersion.builder().id(3L).build()));

        SceneStatsResponse stats = service.getSceneStats(3L);

        assertThat(stats.windowFrom()).isNull();
        assertThat(stats.windowTo()).isNull();
        assertThat(stats.judgedCount()).isZero();
        assertThat(stats.overrideRate()).isNull();
        assertThat(stats.scenes()).extracting(SceneStat::sceneType)
                .containsExactly(SceneType.values());
        assertThat(stats.scenes()).allSatisfy(s -> {
            assertThat(s.aiJudgedCount()).isZero();
            assertThat(s.overrideRate()).isNull();
        });
        verify(sceneClassificationLogRepository, never())
                .findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(any());
    }

    @Test
    @DisplayName("生效中版本：區間至今、以台北 00:00 起算；每品項只看最新一筆")
    void currentVersionCountsLatestPerProduct() {
        givenVersion(2L, V2_FROM, null);
        // 依 createdAt 遞減。品項 1 先被覆寫、後重判 → 最新那筆未覆寫，不算覆寫
        when(sceneClassificationLogRepository.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(V2_FROM_UTC))
                .thenReturn(List.of(
                        log(1L, SceneType.VIRAL, SceneType.VIRAL, false),
                        log(2L, SceneType.VIRAL, SceneType.FESTIVAL, true),
                        log(3L, SceneType.REPLENISHMENT, SceneType.REPLENISHMENT, false),
                        log(4L, null, SceneType.REPLENISHMENT, false),
                        log(1L, SceneType.VIRAL, SceneType.SEASONAL, true)));

        SceneStatsResponse stats = service.getSceneStats(2L);

        assertThat(stats.windowFrom()).isEqualTo(V2_FROM);
        assertThat(stats.windowTo()).isNull();
        assertThat(stats.judgedCount()).isEqualTo(4);
        assertThat(stats.overriddenCount()).isEqualTo(1);
        assertThat(stats.overrideRate()).isEqualByComparingTo("0.25");
        assertThat(stats.aiFailedCount()).isEqualTo(1);

        SceneStat viral = scene(stats, SceneType.VIRAL);
        assertThat(viral.aiJudgedCount()).isEqualTo(2);
        assertThat(viral.overriddenCount()).isEqualTo(1);
        assertThat(viral.overrideRate()).isEqualByComparingTo("0.5");
        assertThat(viral.finalCount()).isEqualTo(1);

        // 被覆寫成節慶型：AI 沒判過節慶型，但最終有 1 項落在這組
        SceneStat festival = scene(stats, SceneType.FESTIVAL);
        assertThat(festival.aiJudgedCount()).isZero();
        assertThat(festival.overrideRate()).isNull();
        assertThat(festival.finalCount()).isEqualTo(1);

        // AI 失敗退回常態補貨：不計入 AI 判定數，但計入最終品項數
        SceneStat replenishment = scene(stats, SceneType.REPLENISHMENT);
        assertThat(replenishment.aiJudgedCount()).isEqualTo(1);
        assertThat(replenishment.finalCount()).isEqualTo(2);

        assertThat(scene(stats, SceneType.SEASONAL).finalCount()).isZero();
    }

    @Test
    @DisplayName("已被取代的版本：區間終點＝下一版生效日（不含）")
    void supersededVersionIsBoundedByNextVersion() {
        LocalDate v1From = LocalDate.of(2026, 2, 24);
        givenVersion(1L, v1From, V2_FROM);
        when(sceneClassificationLogRepository
                .findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                        Instant.parse("2026-02-23T16:00:00Z"), V2_FROM_UTC))
                .thenReturn(List.of(log(7L, SceneType.SEASONAL, SceneType.SEASONAL, false)));

        SceneStatsResponse stats = service.getSceneStats(1L);

        assertThat(stats.windowFrom()).isEqualTo(v1From);
        assertThat(stats.windowTo()).isEqualTo(V2_FROM);
        assertThat(stats.judgedCount()).isEqualTo(1);
        assertThat(stats.overrideRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(scene(stats, SceneType.SEASONAL).aiJudgedCount()).isEqualTo(1);
    }

    private void givenVersion(Long id, LocalDate from, LocalDate nextFrom) {
        when(weightVersionRepository.findById(id))
                .thenReturn(Optional.of(WeightVersion.builder().id(id).effectiveFrom(from).build()));
        when(weightVersionRepository.findFirstByEffectiveFromGreaterThanOrderByEffectiveFromAsc(from))
                .thenReturn(nextFrom == null
                        ? Optional.empty()
                        : Optional.of(WeightVersion.builder().id(id + 1).effectiveFrom(nextFrom).build()));
    }

    private static SceneClassificationLog log(
            Long productId, SceneType ai, SceneType finalScene, boolean overridden) {
        return SceneClassificationLog.builder()
                .product(Product.builder().id(productId).build())
                .aiSceneType(ai)
                .finalSceneType(finalScene)
                .overriddenBy(overridden ? AppUser.builder().id(5L).build() : null)
                .build();
    }

    private static SceneStat scene(SceneStatsResponse stats, SceneType type) {
        return stats.scenes().stream().filter(s -> s.sceneType() == type).findFirst().orElseThrow();
    }
}
