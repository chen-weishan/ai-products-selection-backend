package com.example.ssds.api.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ssds.core.domain.AlertStatus;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.RiskAlert;
import com.example.ssds.infra.repository.RiskAlertRepository;

import lombok.RequiredArgsConstructor;

/**
 * 風險示警的唯一寫入入口，集中 FR-10-2／AC-10-6 的去重規則，
 * 避免每一種示警類型各寫一份而慢慢分岔。
 *
 * <p><b>去重規則</b>（同一品項、同一 {@code risk_type}，窗為 7 日）：
 * <ul>
 *   <li>窗內有 {@code OPEN}：更新那一筆的嚴重度、觸發值與偵測時間，不新開</li>
 *   <li>窗內有已處理的（{@code ACKNOWLEDGED}／{@code IGNORED}）：<b>不新開也不改動</b>。
 *       規格書字面只看 OPEN，但那會讓「忽略」只管到下一次評分——條件仍成立時隔天又開一筆新的。
 *       已處理者的窗以 {@code handled_at} 起算（沒有就退回 {@code detected_at}），
 *       所以「忽略後 7 日內安靜，之後條件仍成立才重新開立」</li>
 *   <li>窗內什麼都沒有：新開一筆 {@code OPEN}</li>
 * </ul>
 *
 * <p><b>併發</b>：去重是「先查再寫」，所以查詢前先對（品項, 類型）取交易級 advisory lock
 * （{@link RiskAlertLocks}），同時產生同一筆示警的兩個交易會依序執行，不會各開一筆。
 *
 * <p>嚴重度也跟著更新（規格只寫更新觸發值與偵測時間）：評論風險由 MEDIUM 升到 HIGH 時，
 * 示警若還停在 MEDIUM 就等於把最該注意的狀況藏起來。
 */
@Service
@RequiredArgsConstructor
public class RiskAlertWriter {

    /** FR-10-2：同一品項同一 risk_type 的去重窗。 */
    public static final Duration DEDUP_WINDOW = Duration.ofDays(7);

    /** {@code risk_alert.trigger_value} 的欄位長度（V17）。 */
    static final int TRIGGER_VALUE_MAX_LENGTH = 100;

    private final RiskAlertRepository riskAlertRepository;
    private final RiskAlertLocks locks;

    public enum Outcome {
        /** 新開了一筆 OPEN。 */
        CREATED,
        /** 窗內已有 OPEN，更新了那一筆。 */
        REFRESHED,
        /** 窗內已有已處理的示警，不重開。 */
        SUPPRESSED
    }

    /**
     * @param detectedAt 這次偵測的時刻。由呼叫端傳入而非取 {@code Instant.now()}，
     *                   同一批次的全部品項才會落在同一個時間窗
     */
    @Transactional
    public Outcome raise(
            Product product,
            String riskType,
            Severity severity,
            String triggerValue,
            Instant detectedAt) {

        if (!RiskTypes.ALL.contains(riskType)) {
            throw new IllegalArgumentException("未知的示警類型：" + riskType);
        }
        String trigger = truncate(triggerValue);

        // 先取鎖再查：單筆評分與 06:30 排程可能同時處理同一品項同一類型，
        // 沒有這把鎖，兩邊都查到「窗內沒有」就會各開一筆 OPEN
        locks.lock(product.getId(), riskType);

        List<RiskAlert> inWindow = riskAlertRepository.findWithinDedupWindow(
                product.getId(), riskType, detectedAt.minus(DEDUP_WINDOW), PageRequest.of(0, 1));

        if (!inWindow.isEmpty()) {
            RiskAlert existing = inWindow.get(0);
            if (existing.getStatus() != AlertStatus.OPEN) {
                return Outcome.SUPPRESSED;
            }
            existing.setSeverity(severity);
            existing.setTriggerValue(trigger);
            existing.setDetectedAt(detectedAt);
            riskAlertRepository.save(existing);
            return Outcome.REFRESHED;
        }

        riskAlertRepository.save(RiskAlert.builder()
                .product(product)
                .riskType(riskType)
                .severity(severity)
                .triggerValue(trigger)
                .status(AlertStatus.OPEN)
                .detectedAt(detectedAt)
                .build());
        return Outcome.CREATED;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= TRIGGER_VALUE_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, TRIGGER_VALUE_MAX_LENGTH - 1) + "…";
    }
}
