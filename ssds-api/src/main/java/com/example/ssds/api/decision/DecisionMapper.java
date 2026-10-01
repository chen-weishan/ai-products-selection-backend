package com.example.ssds.api.decision;

import com.example.ssds.api.decision.dto.CampaignResultResponse;
import com.example.ssds.api.decision.dto.DecisionResponse;
import com.example.ssds.api.decision.dto.DecisionStage;
import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.CampaignResult;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.DecisionRecord;
import com.example.ssds.infra.entity.ProductScore;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** FR-11 entity → DTO 轉換。純函式，不查資料庫。 */
final class DecisionMapper {

    /** 規格書 §3.2：系統時區統一 Asia/Taipei。 */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");

    /** §FR-11-2：結案日 + 7 日仍未回填者才算逾期。 */
    static final int FEEDBACK_GRACE_DAYS = 7;

    private DecisionMapper() {
    }

    static DecisionResponse toResponse(DecisionRecord record, LocalDate today) {
        ProductScore score = record.getScore();
        Category category = record.getProduct().getCategory();
        CampaignResult result = record.getResult();
        AppUser reviewer = record.getReviewedBy();
        return new DecisionResponse(
                record.getId(),
                record.getProduct().getId(),
                record.getProduct().getName(),
                category == null ? null : category.getName(),
                record.getProduct().getStatus(),
                record.getDecision(),
                record.getAiAction(),
                record.isFollowedAi(),
                record.getAiQtyMin(),
                record.getAiQtyMax(),
                record.getFirstOrderQty(),
                record.getExpectedListDate(),
                record.getCampaignEndDate(),
                record.getReason(),
                record.getDecidedBy().getId(),
                record.getDecidedBy().getDisplayName(),
                toDisplayTime(record.getDecidedAt()),
                reviewer == null ? null : reviewer.getId(),
                reviewer == null ? null : reviewer.getDisplayName(),
                toDisplayTime(record.getReviewedAt()),
                score.getId(),
                score.getPeriod(),
                score.getSceneType(),
                score.getFinalScore(),
                score.getGrade(),
                stageOf(record),
                overdueDays(record, today),
                result == null ? null : toResult(result));
    }

    static DecisionStage stageOf(DecisionRecord record) {
        if (record.getDecision() != DecisionType.ADOPT) {
            return DecisionStage.NO_CAMPAIGN;
        }
        if (record.getResult() != null) {
            return DecisionStage.COMPLETED;
        }
        if (record.getCampaignEndDate() != null) {
            return DecisionStage.PENDING_RESULT;
        }
        return record.getProduct().getStatus() == ProductStatus.LISTED
                ? DecisionStage.IN_CAMPAIGN
                : DecisionStage.AWAITING_LAUNCH;
    }

    /**
     * 逾期天數 = 今日 − 結案日 − 7（§FR-11-2）。與儀表板待辦區
     * （DashboardService#getOverdueCampaigns）同一條公式，兩處數字必須一致。
     */
    static Integer overdueDays(DecisionRecord record, LocalDate today) {
        if (stageOf(record) != DecisionStage.PENDING_RESULT) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(record.getCampaignEndDate(), today) - FEEDBACK_GRACE_DAYS;
        return days > 0 ? (int) days : null;
    }

    static CampaignResultResponse toResult(CampaignResult result) {
        AppUser filledBy = result.getFilledBy();
        return new CampaignResultResponse(
                result.getActualQty(),
                result.getSelloutStatus(),
                result.getReturnRate(),
                result.getRealizedMarginRate(),
                result.getPostNoteCode(),
                result.getPostNoteText(),
                filledBy == null ? null : filledBy.getId(),
                filledBy == null ? null : filledBy.getDisplayName(),
                toDisplayTime(result.getFilledAt()));
    }

    static OffsetDateTime toDisplayTime(Instant value) {
        return value == null ? null : value.atZone(BUSINESS_ZONE).toOffsetDateTime();
    }
}
