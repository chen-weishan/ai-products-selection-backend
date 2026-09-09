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
import java.util.List;
import java.util.Optional;

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

    @InjectMocks
    private ScoreQueryService service;

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
