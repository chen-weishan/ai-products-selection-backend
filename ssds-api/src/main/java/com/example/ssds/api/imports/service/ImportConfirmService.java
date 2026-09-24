package com.example.ssds.api.imports.service;

import com.example.ssds.api.imports.dto.ImportBatchResponse;
import com.example.ssds.api.imports.dto.ImportConfirmRequest;
import com.example.ssds.infra.entity.ImportBatch;
import org.springframework.stereotype.Service;

@Service
public class ImportConfirmService {
    private final ImportBatchLifecycleService lifecycleService;
    private final ImportExecutionService executionService;
    private final ImportBatchQueryService queryService;
    private final ImportAsyncCoordinator asyncCoordinator;

    public ImportConfirmService(
            ImportBatchLifecycleService lifecycleService,
            ImportExecutionService executionService,
            ImportBatchQueryService queryService,
            ImportAsyncCoordinator asyncCoordinator
    ) {
        this.lifecycleService = lifecycleService;
        this.executionService = executionService;
        this.queryService = queryService;
        this.asyncCoordinator = asyncCoordinator;
    }

    public ImportBatchResponse confirm(Long batchId, ImportConfirmRequest request) {
        ImportBatch batch = lifecycleService.claim(batchId, request);
        if (batch.isAsync()) {
            try {
                asyncCoordinator.submit(batchId);
            } catch (RuntimeException exception) {
                lifecycleService.fail(batchId, exception.getMessage());
                throw exception;
            }
            return queryService.get(batchId);
        }
        executionService.execute(batchId);
        return queryService.get(batchId);
    }
}
