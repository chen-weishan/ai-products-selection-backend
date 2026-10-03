package com.example.ssds.api.imports.service;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.ImportBatch;
import com.example.ssds.infra.repository.ImportBatchRepository;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ImportRecoveryTest {
    @Test void completedSalesBatchPublishesScoringEventOnlyAtBatchBoundary() {
        var events = mock(ApplicationEventPublisher.class);
        var service = new ImportExecutionService(
                mock(ImportBatchRepository.class),
                mock(com.example.ssds.ingest.importer.ImportStagingStorage.class),
                mock(com.example.ssds.ingest.importer.ImportFileScanner.class),
                mock(ImportPreviewService.class),
                mock(ImportChunkWriter.class),
                mock(ImportBatchLifecycleService.class),
                mock(com.example.ssds.infra.repository.ProductRepository.class),
                events,
                mock(ImportWorkerLock.class),
                Duration.ofMinutes(10),
                Duration.ofMinutes(30));
        var batch = ImportBatch.builder()
                .id(91L)
                .dataType(com.example.ssds.core.domain.ImportDataType.SALES)
                .status(TaskStatus.SUCCEEDED)
                .successRows(600)
                .build();

        service.publish(batch, Set.of(1L, 2L));

        verify(events).publishEvent(isA(com.example.ssds.api.imports.event.ImportCompletedEvent.class));
        verify(events).publishEvent(new com.example.ssds.infra.event.SalesImportCompletedEvent(91L));
        verifyNoMoreInteractions(events);
    }

    @Test void salesBatchWithoutCommittedRowsDoesNotPublishScoringEvent() {
        var events = mock(ApplicationEventPublisher.class);
        var service = new ImportExecutionService(
                mock(ImportBatchRepository.class),
                mock(com.example.ssds.ingest.importer.ImportStagingStorage.class),
                mock(com.example.ssds.ingest.importer.ImportFileScanner.class),
                mock(ImportPreviewService.class),
                mock(ImportChunkWriter.class),
                mock(ImportBatchLifecycleService.class),
                mock(com.example.ssds.infra.repository.ProductRepository.class),
                events,
                mock(ImportWorkerLock.class),
                Duration.ofMinutes(10),
                Duration.ofMinutes(30));
        var batch = ImportBatch.builder()
                .id(92L)
                .dataType(com.example.ssds.core.domain.ImportDataType.SALES)
                .status(TaskStatus.FAILED)
                .successRows(0)
                .build();

        service.publish(batch, Set.of());

        verify(events).publishEvent(isA(com.example.ssds.api.imports.event.ImportCompletedEvent.class));
        verifyNoMoreInteractions(events);
    }

    @Test void expiredQueueUsesPersistedMappingTimeAndFailsOnlyUnderLock() throws Exception {
        var batches = mock(ImportBatchRepository.class);
        var storage = mock(com.example.ssds.ingest.importer.ImportStagingStorage.class);
        var lifecycle = mock(ImportBatchLifecycleService.class);
        var lock = mock(ImportWorkerLock.class);
        var lease = mock(ImportWorkerLock.Lease.class);
        when(lock.tryAcquire(1L)).thenReturn(lease);
        when(batches.findById(1L)).thenReturn(java.util.Optional.of(
                ImportBatch.builder().id(1L).status(TaskStatus.RUNNING).build()));
        when(storage.mappingSavedAt(1L)).thenReturn(java.time.Instant.EPOCH);
        var service = new ImportExecutionService(batches, storage,
                mock(com.example.ssds.ingest.importer.ImportFileScanner.class),
                mock(ImportPreviewService.class), mock(ImportChunkWriter.class), lifecycle,
                mock(com.example.ssds.infra.repository.ProductRepository.class),
                mock(ApplicationEventPublisher.class), lock, Duration.ofMinutes(10), Duration.ofMinutes(30));
        service.expireQueued(1L);
        verify(lifecycle).fail(eq(1L), contains("排隊"));
        verify(lease).check();
        verify(lease).close();
        // A partially committed batch also has a bounded wait after recovery.
        when(batches.findById(1L)).thenReturn(java.util.Optional.of(
                ImportBatch.builder().id(1L).successRows(500).status(TaskStatus.RUNNING).build()));
        service.expireQueued(1L, java.time.Instant.EPOCH);
        verify(lifecycle, times(2)).fail(eq(1L), contains("排隊"));
    }

    @Test void busyWorkerLockSkipsExecutionWithoutChangingBatch() throws Exception {
        var source = mock(javax.sql.DataSource.class);
        var connection = mock(java.sql.Connection.class);
        var statement = mock(java.sql.PreparedStatement.class);
        var result = mock(java.sql.ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.getBoolean(1)).thenReturn(false);
        assertThat(new ImportWorkerLock(source, 30).tryAcquire(9L)).isNull();
        verify(connection).close();
        verify(connection, never()).prepareStatement("select pg_advisory_unlock(?)");
    }

    @Test void saturationDoesNotAbortRecoveryAndRetriesOnNextPoll() {
        var executor = mock(ThreadPoolTaskExecutor.class);
        var execution = mock(ImportExecutionService.class);
        var repository = mock(ImportBatchRepository.class);
        when(repository.findByStatus(TaskStatus.RUNNING)).thenReturn(List.of(
                ImportBatch.builder().id(1L).build(), ImportBatch.builder().id(2L).build()));
        var transactions = mock(ImportTransactionExecutor.class);
        when(transactions.readOnly(any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        when(executor.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException())
                .thenAnswer(i -> new CompletableFuture<Void>());
        var coordinator = new ImportAsyncCoordinator(executor, execution,
                mock(ImportBatchLifecycleService.class), repository,
                mock(ApplicationEventPublisher.class), transactions, Duration.ofMinutes(30), true);
        assertThatCode(coordinator::recoverRunningImports).doesNotThrowAnyException();
        coordinator.pollRunningImports();
        verify(executor, times(3)).submit(any(Runnable.class));
        verify(execution, times(2)).expireQueued(eq(1L), any(java.time.Instant.class));
        verify(execution).expireQueued(eq(2L), any(java.time.Instant.class));
    }

    @Test void disabledPeriodicRecoveryDoesNotQueryRunningImports() {
        var transactions = mock(ImportTransactionExecutor.class);
        var repository = mock(ImportBatchRepository.class);
        var coordinator = new ImportAsyncCoordinator(
                mock(ThreadPoolTaskExecutor.class), mock(ImportExecutionService.class),
                mock(ImportBatchLifecycleService.class), repository,
                mock(ApplicationEventPublisher.class), transactions, Duration.ofMinutes(30), false);

        coordinator.pollRunningImports();

        verifyNoInteractions(transactions, repository);
    }

    @Test void advisoryLockIsReleasedBeforeConnectionReturnsToPool() throws Exception {
        var source = mock(javax.sql.DataSource.class);
        var connection = mock(java.sql.Connection.class);
        var statement = mock(java.sql.PreparedStatement.class);
        var result = mock(java.sql.ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.getBoolean(1)).thenReturn(true);
        var lease = new ImportWorkerLock(source, 30).tryAcquire(9L);
        assertThat(lease).isNotNull();
        verify(connection, never()).close();
        lease.close();
        var order = inOrder(connection, statement);
        order.verify(connection).prepareStatement("select pg_advisory_unlock(?)");
        order.verify(statement).execute();
        order.verify(connection).close();
    }
}
