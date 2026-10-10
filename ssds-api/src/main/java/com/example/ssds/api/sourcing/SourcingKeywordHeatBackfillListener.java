package com.example.ssds.api.sourcing;

import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 交易提交後才回補，避免背景工作讀不到剛建立的 keyword。 */
@Component
public class SourcingKeywordHeatBackfillListener {
    private static final Logger log = LoggerFactory.getLogger(SourcingKeywordHeatBackfillListener.class);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final SourcingKeywordHeatBackfillService backfillService;

    public SourcingKeywordHeatBackfillListener(SourcingKeywordHeatBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Async("sourcingHeatBackfillExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onKeywordObserved(SourcingKeywordObservedEvent event) {
        try {
            backfillService.backfill(event.keywordId(), LocalDate.now(TAIPEI));
        } catch (RuntimeException exception) {
            log.error("S-17 關鍵字七日熱度回補失敗：keywordId={}", event.keywordId(), exception);
        }
    }
}
