package com.example.ssds.api.score;

import static com.example.ssds.api.score.ScoreTestFixtures.allBonusFactors;
import static com.example.ssds.api.score.ScoreTestFixtures.bonusFactor;
import static com.example.ssds.api.score.ScoreTestFixtures.score;
import static com.example.ssds.api.score.ScoreTestFixtures.threshold;
import static com.example.ssds.api.score.ScoreTestFixtures.weightVersion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.score.dto.ScoreRankingRowResponse;
import com.example.ssds.api.score.dto.SimulateRequest;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.ScoreFactor;
import com.example.ssds.infra.repository.GradeThresholdRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.ScoreFactorRepository;
import com.example.ssds.infra.repository.WeightVersionRepository;

/**
 * FR-04 權重版本試算（規格書 §FR-04、§8.2、AC-04-2～AC-04-4、AC-04-7）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScoreSimulationServiceTest {

    @Mock
    private WeightVersionRepository weightVersionRepository;

    @Mock
    private GradeThresholdRepository gradeThresholdRepository;

    @Mock
    private ProductScoreRepository productScoreRepository;

    @Mock
    private ScoreFactorRepository scoreFactorRepository;

    @InjectMocks
    private ScoreSimulationService service;

    /** 版本存在、四榜門檻 A≥85 B≥70、母體為傳入的分數。 */
    private void given(List<ProductScore> scores, List<ScoreFactor> factors) {
        when(weightVersionRepository.findWithProfilesById(3L))
                .thenReturn(Optional.of(weightVersion(3L)));
        when(gradeThresholdRepository.findByVersionId(3L)).thenReturn(
                List.of(threshold(SceneType.VIRAL, "85", "70"),
                        threshold(SceneType.FESTIVAL, "85", "70"),
                        threshold(SceneType.REPLENISHMENT, "85", "70"),
                        threshold(SceneType.SEASONAL, "85", "70")));
        when(productScoreRepository.findRanking(anyString(), any(), any(), any()))
                .thenReturn(new PageImpl<>(scores, PageRequest.of(0, 20), scores.size()));
        when(scoreFactorRepository.findByScoreIdIn(any())).thenReturn(factors);
    }

    private SimulateRequest request(
            Map<SceneType, Map<FactorCode, BigDecimal>> overrides,
            Map<SceneType, SimulateRequest.ThresholdOverride> thresholdOverrides) {
        return new SimulateRequest(3L, "2026W30", SceneType.VIRAL, null,
                overrides, thresholdOverrides, 20);
    }

    private Map<SceneType, Map<FactorCode, BigDecimal>> viralOverride(String... weights) {
        Map<FactorCode, BigDecimal> group = new EnumMap<>(FactorCode.class);
        for (int i = 0; i < weights.length; i++) {
            group.put(ScoreTestFixtures.BONUS_FACTORS[i], new BigDecimal(weights[i]));
        }
        return Map.of(SceneType.VIRAL, group);
    }

    @Nested
    @DisplayName("權重驗證")
    class WeightValidation {

        @Test
        @DisplayName("加總不為 1.000 回 409 WEIGHT_SUM_INVALID（§8.2）")
        void sumMustBeOne() {
            ProductScore s = score(1L, BigDecimal.ZERO);
            given(List.of(s), allBonusFactors(s));

            assertThatThrownBy(() -> service.simulate(
                    request(viralOverride("0.50", "0.10", "0.08", "0.07", "0.15", "0.20"), null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.WEIGHT_SUM_INVALID));
        }

        /**
         * 值域檢查必須排在加總檢查之前，否則負權重會被超過 1 的權重抵銷。
         * 與 FR-08 的 WeightVersionCommandService 同一條規則，試算路徑同樣要擋。
         */
        @Test
        @DisplayName("負權重回 400 VALIDATION_FAILED，不因加總剛好 1.000 而放行")
        void negativeWeightIsRejectedEvenWhenSumIsOne() {
            ProductScore s = score(1L, BigDecimal.ZERO);
            given(List.of(s), allBonusFactors(s));

            assertThatThrownBy(() -> service.simulate(
                    request(viralOverride("-0.50", "1.50", "0.00", "0.00", "0.00", "0.00"), null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("A 級門檻未大於 B 級門檻回 400 VALIDATION_FAILED")
        void thresholdOrderIsEnforced() {
            ProductScore s = score(1L, BigDecimal.ZERO);
            given(List.of(s), allBonusFactors(s));

            assertThatThrownBy(() -> service.simulate(request(null,
                    Map.of(SceneType.VIRAL, new SimulateRequest.ThresholdOverride(
                            new BigDecimal("70"), new BigDecimal("85"))))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("權重版本不存在回 404 RESOURCE_NOT_FOUND")
        void missingVersion() {
            when(weightVersionRepository.findWithProfilesById(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.simulate(request(null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("計分規則")
    class Scoring {

        /**
         * AC-04-2：扣分 ≥ 20 者分級不得為 A（§5.6 硬規則）。
         *
         * <p>六因子皆 100 分、權重加總 1.000 → 加分 100；扣分 20 → 總分 80。
         * A 門檻壓到 70，讓 80 本來落在 A，才看得出壓級真的生效。
         */
        @Test
        @DisplayName("AC-04-2 扣分達 20 時就算分數過 A 門檻也只給 B")
        void penaltyCapSuppressesGradeA() {
            ProductScore s = score(1L, new BigDecimal("20.00"));
            given(List.of(s), allBonusFactors(s));

            List<ScoreRankingRowResponse> rows = service.simulate(request(null,
                    Map.of(SceneType.VIRAL, new SimulateRequest.ThresholdOverride(
                            new BigDecimal("70"), new BigDecimal("50")))));

            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.finalScore()).isEqualByComparingTo("80.00");
                assertThat(row.grade()).isEqualTo(Grade.B);
                assertThat(row.riskSuppressed()).isTrue();
            });
        }

        @Test
        @DisplayName("扣分未達 20 時分數過門檻仍給 A")
        void gradeAIsKeptBelowTheCap() {
            ProductScore s = score(1L, new BigDecimal("19.99"));
            given(List.of(s), allBonusFactors(s));

            List<ScoreRankingRowResponse> rows = service.simulate(request(null,
                    Map.of(SceneType.VIRAL, new SimulateRequest.ThresholdOverride(
                            new BigDecimal("70"), new BigDecimal("50")))));

            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.grade()).isEqualTo(Grade.A);
                assertThat(row.riskSuppressed()).isFalse();
            });
        }

        /**
         * AC-04-3：無資料因子不扣分，其權重依 §5.7 分攤給其餘因子。
         *
         * <p>TREND（權重 0.50）無資料時，其餘五項的權重被放大為 w/0.50，
         * 各因子正規化值皆 100 → 加分仍為 100，而不是 100 − 50。
         */
        @Test
        @DisplayName("AC-04-3 無資料因子不扣分，權重分攤給其餘因子")
        void missingFactorIsRedistributedNotPenalized() {
            ProductScore s = score(1L, BigDecimal.ZERO);
            List<ScoreFactor> factors = new ArrayList<>(allBonusFactors(s));
            factors.set(0, bonusFactor(s, FactorCode.TREND, null, false));
            given(List.of(s), factors);

            List<ScoreRankingRowResponse> rows = service.simulate(request(null, null));

            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.bonusSubtotal()).isEqualByComparingTo("100.00");
                assertThat(row.factors())
                        .filteredOn(f -> f.factorCode() == FactorCode.TREND)
                        .singleElement()
                        .satisfies(bar -> {
                            assertThat(bar.dataAvailable()).isFalse();
                            // 權重已被分攤掉，長條顯示灰底
                            assertThat(bar.weight()).isNull();
                        });
            });
        }

        /** §5.7：六個加分因子缺 4 項以上就不產生分數，整筆不列入結果。 */
        @Test
        @DisplayName("可用加分因子少於 3 項時該筆不列入試算結果")
        void tooFewFactorsProducesNoRow() {
            ProductScore s = score(1L, BigDecimal.ZERO);
            List<ScoreFactor> factors = new ArrayList<>(allBonusFactors(s));
            for (int i = 0; i < 4; i++) {
                factors.set(i, bonusFactor(s, ScoreTestFixtures.BONUS_FACTORS[i], null, false));
            }
            given(List.of(s), factors);

            assertThat(service.simulate(request(null, null))).isEmpty();
        }

        /** AC-04-7：penalty_subtotal 於 API 回應中為正值，負號只在 UI。 */
        @Test
        @DisplayName("AC-04-7 扣分小計回正值，且不隨權重版本重算")
        void penaltyStaysPositiveAndUnchanged() {
            ProductScore s = score(1L, new BigDecimal("4.00"));
            given(List.of(s), allBonusFactors(s));

            assertThat(service.simulate(request(null, null)))
                    .singleElement()
                    .satisfies(row -> assertThat(row.penaltySubtotal()).isEqualByComparingTo("4.00"));
        }

        @Test
        @DisplayName("總分下限為 0：扣分大於加分時不回負分")
        void finalScoreIsFlooredAtZero() {
            ProductScore s = score(1L, new BigDecimal("40.00"));
            List<ScoreFactor> factors = List.of(
                    bonusFactor(s, FactorCode.TREND, new BigDecimal("10"), true),
                    bonusFactor(s, FactorCode.MARGIN, new BigDecimal("10"), true),
                    bonusFactor(s, FactorCode.CVR, new BigDecimal("10"), true));
            given(List.of(s), factors);

            assertThat(service.simulate(request(null, null)))
                    .singleElement()
                    .satisfies(row -> assertThat(row.finalScore()).isEqualByComparingTo("0.00"));
        }

        /** 分數變了，資料庫的排序已失效，必須在 Java 端重排。 */
        @Test
        @DisplayName("結果依重算後的總分由高到低重新排序")
        void rowsAreReSortedByRecomputedScore() {
            ProductScore low = score(1L, new BigDecimal("10.00"));
            ProductScore high = score(2L, BigDecimal.ZERO);
            List<ScoreFactor> factors = new ArrayList<>(allBonusFactors(low));
            factors.addAll(allBonusFactors(high));
            given(List.of(low, high), factors);

            List<ScoreRankingRowResponse> rows = service.simulate(request(null, null));

            assertThat(rows).extracting(ScoreRankingRowResponse::scoreId).containsExactly(2L, 1L);
        }
    }

    /** AC-04-4：切換權重版本試算不寫入資料庫。 */
    @Test
    @DisplayName("AC-04-4 試算全程不寫入資料庫")
    void simulationNeverWrites() {
        ProductScore s = score(1L, BigDecimal.ZERO);
        given(List.of(s), allBonusFactors(s));

        service.simulate(request(null, null));

        verify(productScoreRepository, never()).save(any());
        verify(productScoreRepository, never()).saveAll(any());
        verify(scoreFactorRepository, never()).save(any());
        verify(weightVersionRepository, never()).save(any());
        verify(gradeThresholdRepository, never()).save(any());
    }

    @Test
    @DisplayName("母體為空時回空清單，不打因子查詢")
    void emptyPopulationShortCircuits() {
        when(weightVersionRepository.findWithProfilesById(3L))
                .thenReturn(Optional.of(weightVersion(3L)));
        when(gradeThresholdRepository.findByVersionId(3L))
                .thenReturn(List.of(threshold(SceneType.VIRAL, "85", "70")));
        Page<ProductScore> empty = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(productScoreRepository.findRanking(anyString(), any(), any(), any())).thenReturn(empty);

        assertThat(service.simulate(request(null, null))).isEmpty();
        verify(scoreFactorRepository, never()).findByScoreIdIn(any());
    }
}
