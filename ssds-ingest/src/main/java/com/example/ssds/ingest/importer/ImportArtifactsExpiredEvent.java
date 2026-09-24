package com.example.ssds.ingest.importer;

import java.util.Set;

/** staging 清理完成後通知應用層同步更新 ImportBatch，不讓 ingest 反向依賴 infra。 */
public record ImportArtifactsExpiredEvent(Set<Long> batchIds) {
    public ImportArtifactsExpiredEvent {
        batchIds = Set.copyOf(batchIds);
    }
}
