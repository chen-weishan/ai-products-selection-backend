package com.example.ssds.api.score;

import static com.example.ssds.api.score.ScoreTestFixtures.allBonusFactors;
import static com.example.ssds.api.score.ScoreTestFixtures.penaltyFactor;
import static com.example.ssds.api.score.ScoreTestFixtures.score;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import com.example.ssds.api.score.dto.ScoreDeductionsResponse;
import com.example.ssds.api.score.dto.ScoreRankingRowResponse;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.ScoreFactor;
import com.example.ssds.infra.repository.ProductScoreRepository;
import com.example.ssds.infra.repository.ScoreFactorRepository;

/**
 * FR-04 排行與扣分明細的讀取規則（規格書 §FR-04、AC-04-1、AC-04-6、AC-04-7）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScoreQueryServiceTest {

    @Mock
    private ProductScoreRepository productScoreRepository;

    @Mock
    private ScoreFactorRepository scoreFactorRepository;

    @Mock
    private SceneOverrideLookup sceneOverrideLookup;

    @InjectMocks
    private ScoreQueryService service;

    /** 預設「沒有任何品項被人工覆寫」；需要覆寫標記的測試各自重新 stub。 */
    @BeforeEach
    void noSceneOverrides() {
        when(sceneOverrideLookup.overriddenProductIds(anyString(), any())).thenReturn(Set.of());
    }

    /**
     * 空頁短路：不做這件事會送出 {@code in ()} 這種不合法的 SQL。
     * 查無資料是空頁，不是 404。
     */
    @Test
    @DisplayName("排行查無資料回空頁，且不查因子")
    void emptyRankingDoesNotQueryFactors() {
        when(productScoreRepository.findRanking(anyString(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        Page<ScoreRankingRowResponse> page =
                service.ranking("2026W30", SceneType.VIRAL, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verify(scoreFactorRepository, never()).findByScoreIdIn(any());
    }

    @Test
    @DisplayName("排行摘要統計完整篩選結果的 A／B／C 分布")
    void rankingSummaryCountsAllGrades() {
        when(productScoreRepository.countRankingByGrade(
                "2026W38", SceneType.VIRAL, 10L)).thenReturn(List.of(
                        new Object[] {Grade.A, 8L},
                        new Object[] {Grade.B, 14L},
                        new Object[] {Grade.C, 16L}));

        var summary = service.rankingSummary("2026W38", SceneType.VIRAL, 10L);

        assertThat(summary.totalCount()).isEqualTo(38);
        assertThat(summary.gradeACount()).isEqualTo(8);
        assertThat(summary.gradeBCount()).isEqualTo(14);
        assertThat(summary.gradeCCount()).isEqualTo(16);
    }

    /**
     * 排行列只帶加分因子的長條；扣分是獨立卡片（§FR-04），
     * 由 GET /scores/{id}/deductions 另外供應。
     */
    @Test
    @DisplayName("排行列只含六個加分因子，扣分因子不混進長條")
    void rankingRowsCarryBonusFactorsOnly() {
        ProductScore s = score(1L, new BigDecimal("4.00"));
        List<ScoreFactor> factors = new ArrayList<>(allBonusFactors(s));
        factors.add(penaltyFactor(s, FactorCode.LOGISTICS_RISK, new BigDecimal("4.00"), true));

        when(productScoreRepository.findRanking(anyString(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(s), PageRequest.of(0, 20), 1));
        when(scoreFactorRepository.findByScoreIdIn(any())).thenReturn(factors);

        ScoreRankingRowResponse row =
                service.ranking("2026W30", SceneType.VIRAL, null, PageRequest.of(0, 20))
                        .getContent().get(0);

        assertThat(row.factors()).hasSize(6)
                .noneMatch(bar -> bar.factorCode().isPenalty());
        // AC-04-7：扣分小計為正值，負號只在 UI
        assertThat(row.penaltySubtotal()).isEqualByComparingTo("4.00");
    }

    /**
     * §FR-04 顯示內容表指定長條「依序為熱度斜率、毛利、轉換率、價格帶適配、節慶窗、氣候」，
     * 也就是 {@code FactorCode} 的宣告順序。
     *
     * <p>刻意用「反序」餵進去：{@code findByScoreIdIn} 沒有 order by，回傳順序由
     * 資料庫決定，不排序的話畫面上的長條每次查詢都可能換位置。
     */
    @Test
    @DisplayName("排行列的因子長條依 FR-04 指定順序排，不受資料庫回傳順序影響")
    void rankingRowSortsFactorBars() {
        ProductScore s = score(1L, new BigDecimal("4.00"));
        List<ScoreFactor> shuffled = new ArrayList<>(allBonusFactors(s));
        Collections.reverse(shuffled);

        when(productScoreRepository.findRanking(anyString(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(s), PageRequest.of(0, 20), 1));
        when(scoreFactorRepository.findByScoreIdIn(any())).thenReturn(shuffled);

        ScoreRankingRowResponse row =
                service.ranking("2026W30", SceneType.VIRAL, null, PageRequest.of(0, 20))
                        .getContent().get(0);

        assertThat(row.factors()).extracting(bar -> bar.factorCode())
                .containsExactly(FactorCode.TREND, FactorCode.MARGIN, FactorCode.CVR,
                        FactorCode.PRICE_FIT, FactorCode.FESTIVAL, FactorCode.CLIMATE);
    }

    /** §FR-04 顯示內容表：情境判定「經人工覆寫者附標記」。 */
    @Test
    @DisplayName("情境判定經人工覆寫的品項帶 sceneOverridden 標記")
    void rankingRowFlagsManuallyOverriddenScene() {
        ProductScore overridden = score(1L, new BigDecimal("4.00"));
        ProductScore plain = score(2L, new BigDecimal("4.00"));

        when(productScoreRepository.findRanking(anyString(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(overridden, plain), PageRequest.of(0, 20), 2));
        when(scoreFactorRepository.findByScoreIdIn(any())).thenReturn(List.of());
        when(sceneOverrideLookup.overriddenProductIds(anyString(), any())).thenReturn(Set.of(1L));

        List<ScoreRankingRowResponse> rows =
                service.ranking("2026W30", SceneType.VIRAL, null, PageRequest.of(0, 20))
                        .getContent();

        assertThat(rows).extracting(ScoreRankingRowResponse::sceneOverridden)
                .containsExactly(true, false);
    }

    /** AC-04-1：總分等於加分小計減扣分小計。 */
    @Test
    @DisplayName("AC-04-1 排行列的加分、扣分、總分三欄取資料庫既有值")
    void rankingRowKeepsStoredScores() {
        ProductScore s = score(1L, new BigDecimal("4.00"));
        when(productScoreRepository.findRanking(anyString(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(s), PageRequest.of(0, 20), 1));
        when(scoreFactorRepository.findByScoreIdIn(any())).thenReturn(allBonusFactors(s));

        ScoreRankingRowResponse row =
                service.ranking("2026W30", null, null, PageRequest.of(0, 20))
                        .getContent().get(0);

        assertThat(row.bonusSubtotal()).isEqualByComparingTo("86.89");
        assertThat(row.finalScore())
                .isEqualByComparingTo(row.bonusSubtotal().subtract(row.penaltySubtotal()));
    }

    /**
     * 小計一律取 product_score.penalty_subtotal，不是把明細加總——
     * 兩者不保證一致（可能有小計卻沒有任何明細列）。
     */
    @Test
    @DisplayName("扣分小計取欄位原值，不由明細加總推算")
    void deductionSubtotalComesFromTheColumn() {
        ProductScore s = score(1L, new BigDecimal("20.00"));
        when(productScoreRepository.findById(1L)).thenReturn(Optional.of(s));
        when(scoreFactorRepository.findByScoreIdAndPenalty(1L, true)).thenReturn(List.of());

        ScoreDeductionsResponse response = service.deductions(1L);

        assertThat(response.items()).isEmpty();
        assertThat(response.penaltySubtotal()).isEqualByComparingTo("20.00");
        // §5.6 硬規則由後端判定後回布林，前端不自己比門檻
        assertThat(response.riskSuppressed()).isTrue();
    }

    /** §5.7：沒資料就不扣分，不是扣 0 分——penaltyValue 為 null 而非 0。 */
    @Test
    @DisplayName("無資料的扣分項回 null 而非 0，UI 才能標示未評估")
    void unavailablePenaltyIsNullNotZero() {
        ProductScore s = score(1L, new BigDecimal("4.00"));
        when(productScoreRepository.findById(1L)).thenReturn(Optional.of(s));
        when(scoreFactorRepository.findByScoreIdAndPenalty(1L, true)).thenReturn(List.of(
                penaltyFactor(s, FactorCode.LOGISTICS_RISK, new BigDecimal("4.00"), true),
                penaltyFactor(s, FactorCode.REVIEW_RISK, null, false)));

        ScoreDeductionsResponse response = service.deductions(1L);

        // 依 FactorCode 宣告順序排：REVIEW_RISK → LOGISTICS_RISK，畫面才不會跳動
        assertThat(response.items()).extracting(ScoreDeductionsResponse.DeductionItem::factorCode)
                .containsExactly(FactorCode.REVIEW_RISK, FactorCode.LOGISTICS_RISK);
        assertThat(response.items().get(0).penaltyValue()).isNull();
        assertThat(response.items().get(0).dataAvailable()).isFalse();
    }

    @Test
    @DisplayName("扣分明細查無此分數回 404 RESOURCE_NOT_FOUND")
    void deductionsOfMissingScore() {
        when(productScoreRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deductions(999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("分數快照查無資料回 404 RESOURCE_NOT_FOUND")
    void snapshotOfMissingScore() {
        when(productScoreRepository.findSnapshot(anyLong(), anyString(), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.snapshot(1L, "2026W30", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("分數詳情回傳原始值、百分位、權重與貢獻值")
    void snapshotCarriesExplainableBonusFactorEvidence() {
        ProductScore s = score(1L, BigDecimal.ZERO);
        ScoreFactor trend = ScoreFactor.builder()
                .score(s)
                .factorCode(FactorCode.TREND)
                .rawValue(new BigDecimal("0.2500"))
                .normalizedValue(new BigDecimal("80.00"))
                .weight(new BigDecimal("0.500"))
                .dataAvailable(true)
                .drivingKeywordId(301L)
                .note("同品類百分位")
                .build();
        when(productScoreRepository.findSnapshot(1L, "2026W30", null))
                .thenReturn(Optional.of(s));
        when(scoreFactorRepository.findByScoreId(1L)).thenReturn(List.of(trend));

        var detail = service.snapshot(1L, "2026W30", null);

        assertThat(detail.bonusFactors()).singleElement().satisfies(factor -> {
            assertThat(factor.rawValue()).isEqualByComparingTo("0.2500");
            assertThat(factor.normalizedValue()).isEqualByComparingTo("80.00");
            assertThat(factor.weight()).isEqualByComparingTo("0.500");
            assertThat(factor.contribution()).isEqualByComparingTo("40.00000");
            assertThat(factor.drivingKeywordId()).isEqualTo(301L);
        });
    }

    /**
     * AC-04-6：同一品項同 period 可有多個適用情境，各情境一筆分數。
     * 歷史查詢帶 scene 時只回該情境那條線。
     */
    @Test
    @DisplayName("AC-04-6 歷史趨勢帶 scene 時只回該情境的分數")
    void historyFiltersByScene() {
        ProductScore viral = score(1L, BigDecimal.ZERO);
        ProductScore festival = score(2L, BigDecimal.ZERO);
        festival.setSceneType(SceneType.FESTIVAL);
        when(productScoreRepository.findByProductIdOrderByCalculatedAtDesc(7L))
                .thenReturn(List.of(viral, festival));

        assertThat(service.history(7L, SceneType.FESTIVAL)).hasSize(1);
        assertThat(service.history(7L, null)).hasSize(2);
    }
}
