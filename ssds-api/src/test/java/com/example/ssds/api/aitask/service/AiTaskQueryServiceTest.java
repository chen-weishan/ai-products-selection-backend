package com.example.ssds.api.aitask.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AiTask;
import com.example.ssds.infra.repository.AiTaskRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AiTaskQueryServiceTest {

    private final AiTaskRepository repository = mock(AiTaskRepository.class);
    private final AiTaskQueryService service = new AiTaskQueryService(repository);

    @Test
    void returnsTaskProgress() {
        AiTask task = AiTask.builder()
                .id(12L)
                .taskType(AiTaskType.FULL_ANALYSIS)
                .status(TaskStatus.RUNNING)
                .totalCount(4)
                .successCount(1)
                .failCount(1)
                .build();
        when(repository.findById(12L)).thenReturn(Optional.of(task));

        var result = service.getStatus(12L);

        assertEquals(TaskStatus.RUNNING, result.status());
        assertEquals(50, result.progressPercent());
    }

    @Test
    void rejectsMissingTask() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.getStatus(99L));
    }
}
