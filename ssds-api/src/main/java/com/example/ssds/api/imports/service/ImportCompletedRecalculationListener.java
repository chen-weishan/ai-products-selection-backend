package com.example.ssds.api.imports.service;

import com.example.ssds.api.imports.event.ImportCompletedEvent;
import com.example.ssds.core.domain.TaskStatus;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** AC-09-3：匯入成功後只觸發確定性重算，不建立 AI task、不呼叫 LLM。 */
@Component
public class ImportCompletedRecalculationListener {

    private final ImportScoreRecalculationService recalculationService;

    public ImportCompletedRecalculationListener(ImportScoreRecalculationService recalculationService) {
        this.recalculationService = recalculationService;
    }

    @EventListener
    public void onImportCompleted(ImportCompletedEvent event) {
        recalculationService.recalculate(event);
    }
}
