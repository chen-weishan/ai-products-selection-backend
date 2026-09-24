package com.example.ssds.api.aitask.execution;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AiTask;
import com.example.ssds.infra.repository.AiTaskRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class AiTaskRecoveryTest {
    @Test
    void republishesPendingAndRunningTasksWithoutForceRefresh() {
        AiTaskRepository tasks = mock(AiTaskRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        AiTask pending = AiTask.builder().id(11L).status(TaskStatus.PENDING).build();
        AiTask running = AiTask.builder().id(12L).status(TaskStatus.RUNNING).build();
        when(tasks.findByStatus(TaskStatus.PENDING)).thenReturn(List.of(pending));
        when(tasks.findByStatus(TaskStatus.RUNNING)).thenReturn(List.of(running));

        new AiTaskRecovery(tasks, events).recoverInterruptedTasks();

        verify(events).publishEvent(new AiTaskCreatedEvent(11L, false));
        verify(events).publishEvent(new AiTaskCreatedEvent(12L, false));
        verify(events, never()).publishEvent(new AiTaskCreatedEvent(11L, true));
    }
}
