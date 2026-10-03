package com.example.ssds.api.imports.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

import com.example.ssds.api.imports.event.ImportCompletedEvent;
import com.example.ssds.core.domain.ImportDataType;
import com.example.ssds.core.domain.TaskStatus;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImportCompletedRecalculationListenerTest {

    private final ImportScoreRecalculationService service = mock(ImportScoreRecalculationService.class);
    private final ImportCompletedRecalculationListener listener =
            new ImportCompletedRecalculationListener(service);

    @Test
    void successfulAndPartialImportsWithAffectedProductsTriggerRecalculation() {
        var succeeded = event(TaskStatus.SUCCEEDED, 2, Set.of(10L));
        var partial = event(TaskStatus.PARTIAL, 1, Set.of(20L));

        listener.onImportCompleted(succeeded);
        listener.onImportCompleted(partial);

        verify(service).recalculate(succeeded);
        verify(service).recalculate(partial);
    }

    @Test
    void failedAndEmptyEventsAlsoCheckDurableWork() {
        listener.onImportCompleted(event(TaskStatus.FAILED, 0, Set.of(10L)));
        listener.onImportCompleted(event(TaskStatus.SUCCEEDED, 1, Set.of()));

        verify(service, org.mockito.Mockito.times(2)).recalculate(org.mockito.ArgumentMatchers.any());
    }

    private ImportCompletedEvent event(TaskStatus status, int successRows, Set<Long> ids) {
        return new ImportCompletedEvent(7L, ImportDataType.SALES, status, successRows, 0, ids);
    }
}
