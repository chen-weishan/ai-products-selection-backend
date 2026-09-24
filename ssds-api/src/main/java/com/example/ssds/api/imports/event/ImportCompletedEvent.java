package com.example.ssds.api.imports.event;

import com.example.ssds.core.domain.ImportDataType;
import com.example.ssds.core.domain.TaskStatus;
import java.util.Set;

/**
 * FR-09 對外串接點。評分重算、FR-18 稽核與通知模組可各自監聽，
 * 匯入流程本身不直接依賴尚未完成的模組。
 */
public record ImportCompletedEvent(
        Long batchId,
        ImportDataType dataType,
        TaskStatus status,
        int successRows,
        int failRows,
        Set<Long> affectedProductIds
) {}
