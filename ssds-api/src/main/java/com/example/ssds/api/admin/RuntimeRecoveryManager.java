package com.example.ssds.api.admin;

import com.example.ssds.api.admin.RuntimeSettingsService.RecoveryConfig;
import com.example.ssds.api.admin.RuntimeSettingsService.RuntimeRecoveryChanged;
import com.example.ssds.api.aitask.execution.AiTaskRecovery;
import com.example.ssds.api.imports.service.ImportAsyncCoordinator;
import com.example.ssds.api.schedule.HeatCompositeCatchUp;
import com.example.ssds.api.schedule.RiskAlertStartupRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** S-14 可即時重新註冊的恢復輪詢，以及只在啟動時執行的補跑入口。 */
@Component
public class RuntimeRecoveryManager {
    private final TaskScheduler scheduler;
    private final RuntimeSettingsService settings;
    private final AiTaskRecovery aiTaskRecovery;
    private final ImportAsyncCoordinator importRecovery;
    private final HeatCompositeCatchUp heatCatchUp;
    private final RiskAlertStartupRunner riskStartupRunner;
    private final List<ScheduledFuture<?>> scheduled = new ArrayList<>();

    public RuntimeRecoveryManager(
            TaskScheduler scheduler,
            RuntimeSettingsService settings,
            AiTaskRecovery aiTaskRecovery,
            ImportAsyncCoordinator importRecovery,
            HeatCompositeCatchUp heatCatchUp,
            RiskAlertStartupRunner riskStartupRunner) {
        this.scheduler = scheduler;
        this.settings = settings;
        this.aiTaskRecovery = aiTaskRecovery;
        this.importRecovery = importRecovery;
        this.heatCatchUp = heatCatchUp;
        this.riskStartupRunner = riskStartupRunner;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        RecoveryConfig config = settings.recoveryConfig();
        aiTaskRecovery.recoverInterruptedTasks();
        importRecovery.recoverRunningImports();
        if (config.heatCatchUpEnabled()) heatCatchUp.catchUpAfterStartup();
        if (config.riskStartupRunEnabled()) riskStartupRunner.onReady();
        reschedule(config);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void settingsChanged(RuntimeRecoveryChanged event) {
        reschedule(event.config());
    }

    synchronized void reschedule(RecoveryConfig config) {
        scheduled.forEach(future -> future.cancel(false));
        scheduled.clear();
        schedule(config.aiTaskRecoveryPollingEnabled(), config.aiTaskRecoveryPollSeconds(),
                aiTaskRecovery::pollInterruptedTasks);
        schedule(config.importPeriodicRecoveryEnabled(), config.importRecoveryPollSeconds(),
                importRecovery::pollRunningImports);
    }

    private void schedule(boolean enabled, int seconds, Runnable job) {
        if (!enabled) return;
        Duration delay = Duration.ofSeconds(seconds);
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(job, Instant.now().plus(delay), delay);
        if (future != null) scheduled.add(future);
    }
}
