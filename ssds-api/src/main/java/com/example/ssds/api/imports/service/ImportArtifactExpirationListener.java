package com.example.ssds.api.imports.service;

import com.example.ssds.ingest.importer.ImportArtifactsExpiredEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 將檔案生命週期事件同步到資料庫批次狀態。 */
@Component
public class ImportArtifactExpirationListener {
    private final ImportBatchLifecycleService lifecycleService;

    public ImportArtifactExpirationListener(ImportBatchLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @EventListener
    public void onExpired(ImportArtifactsExpiredEvent event) {
        lifecycleService.expireArtifacts(event.batchIds());
    }
}
