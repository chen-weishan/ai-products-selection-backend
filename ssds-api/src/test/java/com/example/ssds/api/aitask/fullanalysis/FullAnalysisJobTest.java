package com.example.ssds.api.aitask.fullanalysis;

import static org.mockito.Mockito.*;

import com.example.ssds.api.aitask.service.AiTaskService;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class FullAnalysisJobTest {
    @Test
    void weeklyScheduleDelegatesTaskCreationAndDoesNotRunWorkerDirectly() {
        AiTaskService tasks = mock(AiTaskService.class);
        when(tasks.createScheduledFullAnalysis()).thenReturn(Optional.empty());

        new FullAnalysisJob(tasks).startWeeklyAnalysis();

        verify(tasks).createScheduledFullAnalysis();
        verifyNoMoreInteractions(tasks);
    }

    @Test
    void dailyContinuationOnlyDelegatesQuotaSkippedSelection() {
        AiTaskService tasks = mock(AiTaskService.class);
        when(tasks.resumeQuotaSkippedFullAnalysis()).thenReturn(Optional.empty());

        new FullAnalysisJob(tasks).resumeQuotaSkippedItems();

        verify(tasks).resumeQuotaSkippedFullAnalysis();
        verifyNoMoreInteractions(tasks);
    }
}
