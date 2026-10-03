package com.example.ssds.api.heat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.example.ssds.api.heat.HeatCompositionRecalculationService.Result;

/**
 * AC-14-5：熱度來源權重／啟用狀態變動且交易提交後，非同步觸發合成重算與全量重評分。
 * 與 {@code ScoringRecalculationListener} 同一套做法（AFTER_COMMIT ＋ @Async），
 * 不占用 PUT 請求的回應時間；任何例外都只記錄、不外拋（非同步執行緒沒有呼叫端可接）。
 */
@Component
public class HeatCompositionRecalculationListener {

    private static final Logger log = LoggerFactory.getLogger(HeatCompositionRecalculationListener.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final HeatCompositionRecalculationService recalculationService;

    public HeatCompositionRecalculationListener(HeatCompositionRecalculationService recalculationService) {
        this.recalculationService = recalculationService;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCompositionChanged(HeatSourceCompositionChangedEvent event) {
        try {
            HeatCompositionRecalculationService.Result result =
                    recalculationService.recomposeAndRescore(LocalDate.now(TAIPEI), Instant.now());
            log.info("熱度來源 {} 權重變動後重算完成：{}", event.sourceCode(), result);
        } catch (RuntimeException e) {
            log.error("熱度來源 {} 權重變動後重算失敗", event.sourceCode(), e);
        }
    }
}
