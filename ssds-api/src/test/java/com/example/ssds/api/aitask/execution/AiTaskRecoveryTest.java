package com.example.ssds.api.aitask.execution;

import static org.mockito.Mockito.*;

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
        AiTaskWorker worker = mock(AiTaskWorker.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        AiTask pending = AiTask.builder().id(11L).status(TaskStatus.PENDING).build();
        AiTask running = AiTask.builder().id(12L).status(TaskStatus.RUNNING).build();
        when(tasks.findByStatus(TaskStatus.PENDING)).thenReturn(List.of(pending));
        when(tasks.findByStatus(TaskStatus.RUNNING)).thenReturn(List.of(running));

        new AiTaskRecovery(tasks, worker, events, false).recoverInterruptedTasks();

        verify(events).publishEvent(new AiTaskCreatedEvent(11L, false));
        verify(events).publishEvent(new AiTaskCreatedEvent(12L, false));
        verify(events, never()).publishEvent(new AiTaskCreatedEvent(11L, true));
    }

    @Test
    void disabledPollingDoesNotQueryTasksButStartupRecoveryStillDoes() {
        AiTaskRepository tasks = mock(AiTaskRepository.class);
        AiTaskWorker worker = mock(AiTaskWorker.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        AiTaskRecovery recovery = new AiTaskRecovery(tasks, worker, events, false);

        recovery.pollInterruptedTasks();

        verifyNoInteractions(tasks, worker, events);
        recovery.recoverInterruptedTasks();
        verify(tasks).findByStatus(TaskStatus.PENDING);
        verify(tasks).findByStatus(TaskStatus.RUNNING);
    }

    @Test
    void pollingRecoversOnlyTasksWithoutAnActiveWorker() {
        AiTaskRepository tasks = mock(AiTaskRepository.class);
        AiTaskWorker worker = mock(AiTaskWorker.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        AiTask pending = AiTask.builder().id(21L).status(TaskStatus.PENDING).build();
        AiTask running = AiTask.builder().id(22L).status(TaskStatus.RUNNING).build();
        when(tasks.findByStatus(TaskStatus.PENDING)).thenReturn(List.of(pending));
        when(tasks.findByStatus(TaskStatus.RUNNING)).thenReturn(List.of(running));
        when(worker.isTaskActive(22L)).thenReturn(true);

        new AiTaskRecovery(tasks, worker, events, true).pollInterruptedTasks();

        verify(events).publishEvent(new AiTaskCreatedEvent(21L, false));
        verify(events, never()).publishEvent(new AiTaskCreatedEvent(22L, false));
    }
}
