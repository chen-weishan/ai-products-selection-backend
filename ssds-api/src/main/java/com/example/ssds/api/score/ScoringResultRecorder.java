package com.example.ssds.api.score;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ssds.core.domain.AlertStatus;
import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.RiskAlert;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.RiskAlertRepository;

import lombok.RequiredArgsConstructor;

/**
 * §5.7「加分因子缺 4 項以上 → 不產生分數，狀態標示『資料不足，無法評分』」的承接端。
 *
 * <p>那句「標示」在資料層有兩處落點，兩處都要寫，缺一就看不見：
 * <ul>
 *   <li>{@code product.last_scoring_status}（V23）——品項現在算不算得出分數，
 *       供 FR-03 品項清單與 FR-04 排行標示</li>
 *   <li>{@code risk_alert}（risk_type {@code DATA_INSUFFICIENT}，V27）——
 *       可追蹤、可處理的待辦，讓操作人員知道哪些品項卡住、該補什麼資料</li>
 * </ul>
 *
 * <p>只寫欄位不開示警，資料不足的品項會從畫面上整個消失：它們沒有
 * {@code product_score}，不會出現在任何一張榜，而品項清單上的一個灰字狀態
 * 不會有人主動去翻。
 *
 * <p><b>本服務是寫入端</b>，與同套件的 {@code ScoreQueryService}（只讀）、
 * {@code ScoreSimulationService}（絕不寫入）職責相反。由評分批次於每次評分嘗試
 * 結束時呼叫；批次本身尚未實作（FR-06），這裡先把規則與去重寫定，
 * 避免之後散落在批次程式裡各寫一份。
 */
@Service
@RequiredArgsConstructor
public class ScoringResultRecorder {

    private final ProductRepository productRepository;
    private final RiskAlertRepository riskAlertRepository;

    /** V27 新增的示警類型。規格書 §FR-10-1 尚未收錄，理由見該支 migration 的註解。 */
    public static final String RISK_TYPE_DATA_INSUFFICIENT = "DATA_INSUFFICIENT";

    /** FR-10-2：同一品項同一 risk_type 於 7 日內已有 OPEN 示警時不重複開立。 */
    private static final Duration DEDUP_WINDOW = Duration.ofDays(7);

    /**
     * 評分成功。只更新品項狀態，<b>不</b>自動關閉既有的資料不足示警。
     *
     * <p>不自動關閉是刻意的：{@code AlertStatus} 只有 OPEN／ACKNOWLEDGED／IGNORED，
     * 沒有「已解決」。用 IGNORED 代替會汙染 AC-10-2 的語意（那是「人決定忽略」，
     * 且 {@code ignore_reason} 與 {@code handled_by} 都要填，系統沒有使用者身分可填）。
     * 示警留給人處理，下一次評分成功不會再新開一筆，清單不會累積。
     */
    @Transactional
    public void recordScored(Product product, Instant attemptedAt) {
        product.recordScoringAttempt(LastScoringStatus.SCORED, attemptedAt);
        productRepository.save(product);
    }

    /**
     * 資料不足，未產生分數。
     *
     * @param availableFactorCount 六個加分因子中實際有資料的項數（§5.7 門檻為 3）
     * @param attemptedAt          這次評分嘗試的時刻。由呼叫端傳入而非取
     *                             {@code Instant.now()}，同一批次的全部品項才會落在
     *                             同一個時刻，去重窗與批次結果才可比對
     */
    @Transactional
    public void recordInsufficientData(
            Product product, int availableFactorCount, Instant attemptedAt) {

        product.recordScoringAttempt(LastScoringStatus.INSUFFICIENT_DATA, attemptedAt);
        productRepository.save(product);

        String triggerValue = "可用加分因子 " + availableFactorCount + " 項（門檻 3 項）";

        // FR-10-2 去重：7 日內已有 OPEN 的同類示警就更新，不新開一筆。
        // 每天跑一次批次、資料一直補不齊的品項，不做去重會堆出 7 筆一模一樣的示警
        Optional<RiskAlert> existing = riskAlertRepository
                .findFirstByProductIdAndRiskTypeAndStatusAndDetectedAtAfterOrderByDetectedAtDesc(
                        product.getId(), RISK_TYPE_DATA_INSUFFICIENT, AlertStatus.OPEN,
                        attemptedAt.minus(DEDUP_WINDOW));

        if (existing.isPresent()) {
            RiskAlert alert = existing.get();
            alert.setTriggerValue(triggerValue);
            alert.setDetectedAt(attemptedAt);
            riskAlertRepository.save(alert);
            return;
        }

        riskAlertRepository.save(RiskAlert.builder()
                .product(product)
                .riskType(RISK_TYPE_DATA_INSUFFICIENT)
                // MEDIUM 是設計決定、非規格指定，理由見 V27 migration 的註解
                .severity(Severity.MEDIUM)
                .triggerValue(triggerValue)
                .status(AlertStatus.OPEN)
                .detectedAt(attemptedAt)
                .build());
    }
}
