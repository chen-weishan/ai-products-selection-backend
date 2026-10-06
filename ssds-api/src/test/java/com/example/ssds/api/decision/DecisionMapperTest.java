package com.example.ssds.api.decision;

import static com.example.ssds.api.decision.DecisionFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.ssds.api.decision.dto.DecisionStage;
import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SelloutStatus;
import com.example.ssds.infra.entity.DecisionRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AC-11-3 逾期天數與閉環階段推導。 */
class DecisionMapperTest {

    @Test
    @DisplayName("AC-11-3：逾期天數 = 今日 − 結案日 − 7；滿 7 天當天為 0 不算逾期")
    void overdueDays() {
        DecisionRecord record = DecisionFixtures.decision(1L, DecisionType.ADOPT);

        record.setCampaignEndDate(TODAY.minusDays(7));
        assertThat(DecisionMapper.overdueDays(record, TODAY)).isNull();

        record.setCampaignEndDate(TODAY.minusDays(8));
        assertThat(DecisionMapper.overdueDays(record, TODAY)).isEqualTo(1);

        // S-12 示意圖：結案 2026-07-27，逾 12 天 → 今日為 2026-08-15
        record.setCampaignEndDate(java.time.LocalDate.of(2026, 7, 27));
        assertThat(DecisionMapper.overdueDays(record, java.time.LocalDate.of(2026, 8, 15))).isEqualTo(12);
    }

    @Test
    @DisplayName("已回填、未結案、WATCH 都沒有逾期天數")
    void noOverdueOutsidePendingResult() {
        DecisionRecord filled = DecisionFixtures.filled(1L, "80", Grade.A, 1, SelloutStatus.SLOW);
        assertThat(DecisionMapper.overdueDays(filled, TODAY)).isNull();

        DecisionRecord open = DecisionFixtures.decision(2L, DecisionType.ADOPT);
        assertThat(DecisionMapper.overdueDays(open, TODAY)).isNull();

        DecisionRecord watch = DecisionFixtures.decision(3L, DecisionType.WATCH);
        watch.setCampaignEndDate(TODAY.minusDays(30));
        assertThat(DecisionMapper.overdueDays(watch, TODAY)).isNull();
    }

    @Test
    @DisplayName("閉環階段：NO_CAMPAIGN → AWAITING_LAUNCH → IN_CAMPAIGN → PENDING_RESULT → COMPLETED")
    void stages() {
        assertThat(DecisionMapper.stageOf(DecisionFixtures.decision(1L, DecisionType.REJECT)))
                .isEqualTo(DecisionStage.NO_CAMPAIGN);

        DecisionRecord adopt = DecisionFixtures.decision(2L, DecisionType.ADOPT);
        adopt.getProduct().setStatus(ProductStatus.ADOPTED);
        assertThat(DecisionMapper.stageOf(adopt)).isEqualTo(DecisionStage.AWAITING_LAUNCH);
        adopt.getProduct().setStatus(ProductStatus.LISTED);
        assertThat(DecisionMapper.stageOf(adopt)).isEqualTo(DecisionStage.IN_CAMPAIGN);
        adopt.setCampaignEndDate(TODAY);
        assertThat(DecisionMapper.stageOf(adopt)).isEqualTo(DecisionStage.PENDING_RESULT);

        assertThat(DecisionMapper.stageOf(DecisionFixtures.filled(3L, "80", Grade.A, 1, SelloutStatus.SLOW)))
                .isEqualTo(DecisionStage.COMPLETED);
    }
}
