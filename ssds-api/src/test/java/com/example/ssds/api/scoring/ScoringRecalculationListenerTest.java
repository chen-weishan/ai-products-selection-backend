package com.example.ssds.api.scoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.ssds.infra.event.SalesImportCompletedEvent;
import org.junit.jupiter.api.Test;

class ScoringRecalculationListenerTest {
    private final PureScoringBatchService scoring = mock(PureScoringBatchService.class);
    private final ScoringRecalculationListener listener = new ScoringRecalculationListener(scoring);

    @Test
    void weightActivationRunsPureScoringWithoutAnAiTask() {
        listener.handleWeightVersionActivated(new WeightVersionActivatedEvent(9L));

        verify(scoring).evaluateAll(any());
    }

    @Test
    void salesImportOnlyRecalculatesAffectedProducts() {
        listener.handleSalesImportCompleted(new SalesImportCompletedEvent(91L));

        verify(scoring).evaluateImportBatch(org.mockito.ArgumentMatchers.eq(91L), any());
    }
}
