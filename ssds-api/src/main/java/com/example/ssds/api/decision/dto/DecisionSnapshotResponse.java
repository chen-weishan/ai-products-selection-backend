package com.example.ssds.api.decision.dto;

import com.example.ssds.api.score.dto.ScoreDetailResponse;
import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.SceneType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 決策快照（規格書 §FR-11-1 決策快照內容、§8.2 GET /decisions/{id}/snapshot）。
 *
 * <p>分數、分級、九項因子由 {@code decision_record.score_id} join 回 {@code product_score}
 * 與 {@code score_factor}（§5.10 保證評分永不覆寫）；{@code campaign_snapshot} 只提供
 * 三份還原不了的 JSON 與覆寫旗標。兩者合成本回應。
 *
 * <p>{@code score} 重用 FR-04 的 {@link ScoreDetailResponse}：同一筆評分在排行、詳情、
 * 快照三處回同一種形狀，前端可共用因子長條元件。
 */
public record DecisionSnapshotResponse(
        Long decisionId,
        ScoreDetailResponse score,
        Long weightVersionId,
        String weightVersionNo,
        /** AI 判定的情境原型與信心度；該 period 沒有判定紀錄時為 null。 */
        SceneType aiSceneType,
        BigDecimal aiConfidence,
        boolean sceneOverridden,
        String overriddenByName,
        String overrideReason,
        DecisionType decision,
        DecisionType aiAction,
        boolean followedAi,
        Integer aiQtyMin,
        Integer aiQtyMax,
        /** 決策當下各熱度來源狀態，鍵為來源代碼（THREADS 等）。V17 之前的舊列可能為 null。 */
        Map<String, String> sourceAvailability,
        /** 決策當下實際採用的合成權重（已依可用來源重新正規化）。 */
        Map<String, BigDecimal> appliedCompositeWeights,
        AppliedThresholds appliedThresholds,
        String decidedByName,
        OffsetDateTime decidedAt,
        OffsetDateTime snapshotCreatedAt) {

    /** 決策當下該榜的 A／B 門檻。版本沒有定義該情境門檻時兩者為 null。 */
    public record AppliedThresholds(SceneType sceneType, BigDecimal gradeAMin, BigDecimal gradeBMin) {
    }
}
