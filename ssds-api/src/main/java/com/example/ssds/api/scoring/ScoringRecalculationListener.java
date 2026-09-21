package com.example.ssds.api.scoring;

import java.time.Instant;
import com.example.ssds.infra.event.SalesImportCompletedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 將權重生效與資料匯入轉成純計算重評，不經過 AI task／配額。 */
@Component
public class ScoringRecalculationListener {
    private final PureScoringBatchService scoring;

    public ScoringRecalculationListener(PureScoringBatchService scoring) {
        this.scoring = scoring;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleWeightVersionActivated(WeightVersionActivatedEvent event) {
        scoring.evaluateAll(Instant.now());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSalesImportCompleted(SalesImportCompletedEvent event) {
        scoring.evaluateImportBatch(event.importBatchId(), Instant.now());
    }
}
