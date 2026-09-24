package com.example.ssds.api.scoring;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class PureScoringJobTest {
    @Test
    void weeklyJobRunsPureScoringDirectly() {
        PureScoringBatchService scoring = mock(PureScoringBatchService.class);
        when(scoring.evaluateAll(any())).thenReturn(
                new PureScoringBatchService.BatchResult(0, 0, 0, List.of(), List.of()));

        new PureScoringJob(scoring).scoreAllProducts();

        verify(scoring).evaluateAll(any());
    }
}
