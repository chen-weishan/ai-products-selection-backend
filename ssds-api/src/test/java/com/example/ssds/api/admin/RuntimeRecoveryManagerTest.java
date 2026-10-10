package com.example.ssds.api.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.admin.RuntimeSettingsService.RecoveryConfig;
import com.example.ssds.api.aitask.execution.AiTaskRecovery;
import com.example.ssds.api.imports.service.ImportAsyncCoordinator;
import com.example.ssds.api.schedule.HeatCompositeCatchUp;
import com.example.ssds.api.schedule.RiskAlertStartupRunner;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;

class RuntimeRecoveryManagerTest {
    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final RuntimeSettingsService settings = mock(RuntimeSettingsService.class);
    private final AiTaskRecovery aiRecovery = mock(AiTaskRecovery.class);
    private final ImportAsyncCoordinator importRecovery = mock(ImportAsyncCoordinator.class);
    private final HeatCompositeCatchUp heatCatchUp = mock(HeatCompositeCatchUp.class);
    private final RiskAlertStartupRunner riskStartup = mock(RiskAlertStartupRunner.class);
    private final RuntimeRecoveryManager manager = new RuntimeRecoveryManager(
            scheduler, settings, aiRecovery, importRecovery, heatCatchUp, riskStartup);

    @Test
    void startupAlwaysRecoversPersistentWorkAndOnlyRunsEnabledCatchUps() {
        RecoveryConfig config = new RecoveryConfig(true, 300, false, 15, false, true);
        when(settings.recoveryConfig()).thenReturn(config);

        manager.start();

        verify(aiRecovery).recoverInterruptedTasks();
        verify(importRecovery).recoverRunningImports();
        verify(heatCatchUp, never()).catchUpAfterStartup();
        verify(riskStartup).onReady();
        verify(scheduler).scheduleWithFixedDelay(
                any(Runnable.class), any(Instant.class), eq(Duration.ofSeconds(300)));
    }

    @Test
    void changedSettingsCancelOldPollingAndUseNewIntervals() {
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(scheduler)
                .scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        manager.reschedule(new RecoveryConfig(true, 300, false, 15, false, false));

        manager.reschedule(new RecoveryConfig(false, 30, true, 45, false, false));

        verify(future).cancel(false);
        verify(scheduler, times(2)).scheduleWithFixedDelay(
                any(Runnable.class), any(Instant.class), any(Duration.class));
        verify(scheduler).scheduleWithFixedDelay(
                any(Runnable.class), any(Instant.class), eq(Duration.ofSeconds(45)));
    }
}
