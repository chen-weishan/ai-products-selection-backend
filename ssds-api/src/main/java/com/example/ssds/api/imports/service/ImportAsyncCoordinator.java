package com.example.ssds.api.imports.service;

import com.example.ssds.api.imports.event.ImportCompletedEvent;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.ImportBatch;
import com.example.ssds.infra.repository.ImportBatchRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * staging 模式下的持久狀態協調器：啟動及定期恢復 RUNNING 批次，
 * 並中斷超過上限的背景工作。若日後改用訊息佇列，只需替換本類。
 */
@Service
public class ImportAsyncCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ImportAsyncCoordinator.class);

    private final ThreadPoolTaskExecutor executor;
    private final ImportExecutionService executionService;
    private final ImportBatchLifecycleService lifecycleService;
    private final ImportBatchRepository batchRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ImportTransactionExecutor transactions;
    private final Duration timeout;
    private final boolean periodicRecoveryEnabled;
    private final ConcurrentMap<Long, TrackedTask> tasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Instant> waitingSince = new ConcurrentHashMap<>();

    public ImportAsyncCoordinator(
            @Qualifier("importTaskExecutor") ThreadPoolTaskExecutor executor,
            ImportExecutionService executionService,
            ImportBatchLifecycleService lifecycleService,
            ImportBatchRepository batchRepository,
            ApplicationEventPublisher eventPublisher,
            ImportTransactionExecutor transactions,
            @Value("${ssds.import.async-timeout:30m}") Duration timeout,
            @Value("${ssds.import.periodic-recovery-enabled:false}") boolean periodicRecoveryEnabled
    ) {
        this.executor = executor;
        this.executionService = executionService;
        this.lifecycleService = lifecycleService;
        this.batchRepository = batchRepository;
        this.eventPublisher = eventPublisher;
        this.transactions = transactions;
        this.timeout = timeout;
        this.periodicRecoveryEnabled = periodicRecoveryEnabled;
    }

    public synchronized void submit(Long batchId) {
        if (tasks.containsKey(batchId)) return;
        TrackedTask tracked = new TrackedTask();
        Instant queuedAt = waitingSince.computeIfAbsent(batchId, ignored -> Instant.now());
        tasks.put(batchId, tracked);
        try {
            tracked.future = executor.submit(() -> {
                try {
                    synchronized (tracked) {
                        if (tracked.cancelRequested) return;
                        tracked.startedAt = Instant.now();
                    }
                    executionService.expireQueued(batchId, queuedAt);
                    waitingSince.remove(batchId, queuedAt);
                    executionService.execute(batchId);
                } finally {
                    tasks.remove(batchId, tracked);
                }
            });
        } catch (RuntimeException exception) {
            tasks.remove(batchId, tracked);
            if (exception instanceof java.util.concurrent.RejectedExecutionException) {
                log.info("FR-09 executor full; recovery will retry batchId={}", batchId);
                return;
            }
            throw exception;
        }
    }

    /** server restart 後以 DB 的 RUNNING 狀態與 sidecar mapping 恢復未完成工作。 */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverRunningImports() {
        recoverRunningImportsFromDatabase();
    }

    @Scheduled(fixedDelayString = "${ssds.import.recovery-delay:15s}", initialDelayString = "${ssds.import.recovery-delay:15s}")
    public void pollRunningImports() {
        if (!periodicRecoveryEnabled) return;
        recoverRunningImportsFromDatabase();
    }

    private void recoverRunningImportsFromDatabase() {
        var running = transactions.readOnly(() -> batchRepository.findByStatus(TaskStatus.RUNNING));
        var runningIds = running.stream().map(ImportBatch::getId).collect(java.util.stream.Collectors.toSet());
        waitingSince.keySet().removeIf(id -> !runningIds.contains(id) && !tasks.containsKey(id));
        for (ImportBatch batch : running) {
            if (tasks.containsKey(batch.getId())) continue;
            try {
                executionService.expireQueued(batch.getId(),
                        waitingSince.computeIfAbsent(batch.getId(), ignored -> Instant.now()));
                submit(batch.getId());
            } catch (RuntimeException error) {
                log.warn("FR-09 recovery deferred batchId={}", batch.getId(), error);
            }
        }
    }

    @Scheduled(fixedDelayString = "${ssds.import.async-timeout-check-delay:1m}")
    public void cancelTimedOutImports() {
        Instant cutoff = Instant.now().minus(timeout);
        tasks.forEach((batchId, tracked) -> {
            Instant startedAt = tracked.startedAt;
            if (startedAt == null) {
                // The database lock arbitrates a race with a worker starting on any node.
                synchronized (tracked) {
                    if (tracked.startedAt != null) return;
                    executionService.expireQueued(batchId, waitingSince.get(batchId));
                    transactions.readOnly(() -> batchRepository.findById(batchId)).ifPresent(batch -> {
                        if (batch.getStatus() == TaskStatus.RUNNING) return;
                        tracked.cancelRequested = true;
                        if (tracked.future != null) tracked.future.cancel(false);
                        executor.getThreadPoolExecutor().purge();
                        waitingSince.remove(batchId);
                        tasks.remove(batchId, tracked);
                    });
                }
                return;
            }
            if (!startedAt.isBefore(cutoff) || tracked.cancelRequested) return;
            tracked.cancelRequested = true;
            Future<?> future = tracked.future;
            if (future != null) future.cancel(true);
            log.warn("FR-09 import timed out: batchId={}, startedAt={}", batchId, startedAt);
            // Only the lock-owning execution may commit a terminal status.
        });
    }

    private static final class TrackedTask {
        private volatile Instant startedAt;
        private volatile Future<?> future;
        private volatile boolean cancelRequested;
    }
}
