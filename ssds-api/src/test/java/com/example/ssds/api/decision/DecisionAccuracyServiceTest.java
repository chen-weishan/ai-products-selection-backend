package com.example.ssds.api.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ssds.api.decision.dto.DecisionAccuracyResponse;
import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.SelloutStatus;
import com.example.ssds.infra.entity.CampaignSnapshot;
import com.example.ssds.infra.entity.DecisionRecord;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-11-3 五項指標與 AC-11-5 效度警示。 */
class DecisionAccuracyServiceTest {

    @Test
    @DisplayName("皮爾森相關：完全正相關為 1、完全負相關為 -1；樣本 < 2 或無變異為 null")
    void pearson() {
        assertThat(DecisionAccuracyService.pearson(new double[] { 1, 2, 3 }, new double[] { 10, 20, 30 }))
                .isEqualByComparingTo("1");
        assertThat(DecisionAccuracyService.pearson(new double[] { 1, 2, 3 }, new double[] { 30, 20, 10 }))
                .isEqualByComparingTo("-1");
        // 手算：x=(80,70,90) y=(300,200,350) → r ≈ 0.9820
        assertThat(DecisionAccuracyService.pearson(new double[] { 80, 70, 90 }, new double[] { 300, 200, 350 }))
                .isEqualByComparingTo("0.9820");
        assertThat(DecisionAccuracyService.pearson(new double[] { 1 }, new double[] { 1 })).isNull();
        assertThat(DecisionAccuracyService.pearson(new double[] { 5, 5 }, new double[] { 1, 2 })).isNull();
    }

    @Test
    @DisplayName("分母為 0 的比率回 null，不是 0")
    void rateWithZeroDenominator() {
        assertThat(DecisionAccuracyService.rate(0, 0)).isNull();
        assertThat(DecisionAccuracyService.rate(5, 7)).isEqualByComparingTo("0.7143");
    }

    @Test
    @DisplayName("A 級達標率只計 A 級已回填者；EARLY_SELLOUT 與 ON_TIME 算達標")
    void gradeAHitRate() {
        List<DecisionRecord> population = List.of(
                DecisionFixtures.filled(1L, "88", Grade.A, 400, SelloutStatus.EARLY_SELLOUT),
                DecisionFixtures.filled(2L, "85", Grade.A, 300, SelloutStatus.ON_TIME),
                DecisionFixtures.filled(3L, "82", Grade.A, 100, SelloutStatus.SLOW),
                DecisionFixtures.filled(4L, "70", Grade.B, 350, SelloutStatus.EARLY_SELLOUT));

        DecisionAccuracyResponse response = DecisionAccuracyService.compute(population, null, null, null, null, 200);

        assertThat(response.gradeASampleSize()).isEqualTo(3);
        assertThat(response.gradeAHitCount()).isEqualTo(2);
        assertThat(response.gradeAHitRate()).isEqualByComparingTo("0.6667");
        assertThat(response.sampleSize()).isEqualTo(4);
    }

    @Test
    @DisplayName("§FR-11-3：AI 採納率＝全部決策中 followed_ai = true 的比例（無 AI 建議者計入分母）；覆寫率同樣以全部決策為分母")
    void adoptionAndOverrideRates() {
        DecisionRecord followed = DecisionFixtures.decision(1L, DecisionType.ADOPT);
        followed.setAiAction(DecisionType.ADOPT);
        followed.setFollowedAi(true);
        followed.setSnapshot(CampaignSnapshot.builder().sceneOverridden(true).build());

        DecisionRecord disagreed = DecisionFixtures.decision(2L, DecisionType.REJECT);
        disagreed.setAiAction(DecisionType.ADOPT);
        disagreed.setFollowedAi(false);
        disagreed.setSnapshot(CampaignSnapshot.builder().sceneOverridden(false).build());

        DecisionRecord noAi = DecisionFixtures.decision(3L, DecisionType.WATCH);
        noAi.setFollowedAi(false);
        noAi.setSnapshot(CampaignSnapshot.builder().sceneOverridden(false).build());

        DecisionAccuracyResponse response = DecisionAccuracyService.compute(
                List.of(followed, disagreed, noAi), null, null, null, null, 200);

        assertThat(response.aiFollowedCount()).isEqualTo(1);
        assertThat(response.aiAdoptionRate()).isEqualByComparingTo("0.3333");
        assertThat(response.sceneOverrideCount()).isEqualTo(1);
        assertThat(response.sceneOverrideRate()).isEqualByComparingTo("0.3333");
        assertThat(response.totalDecisions()).isEqualTo(3);
        assertThat(response.sampleSize()).isZero();
        assertThat(response.scoreSalesCorrelation()).isNull();
        assertThat(response.gradeAHitRate()).isNull();
    }

    @Test
    @DisplayName("AC-11-5：樣本 < 門檻時帶警示旗標與固定文案；達門檻時沒有")
    void validityWarning() {
        List<DecisionRecord> two = List.of(
                DecisionFixtures.filled(1L, "88", Grade.A, 400, SelloutStatus.ON_TIME),
                DecisionFixtures.filled(2L, "60", Grade.C, 50, SelloutStatus.SLOW));

        DecisionAccuracyResponse below = DecisionAccuracyService.compute(two, null, null, null, null, 200);
        assertThat(below.belowMinSample()).isTrue();
        assertThat(below.validityWarning()).isEqualTo(DecisionAccuracyService.VALIDITY_WARNING);
        assertThat(below.minSample()).isEqualTo(200);

        DecisionAccuracyResponse enough = DecisionAccuracyService.compute(two, null, null, null, null, 2);
        assertThat(enough.belowMinSample()).isFalse();
        assertThat(enough.validityWarning()).isNull();
    }
}
