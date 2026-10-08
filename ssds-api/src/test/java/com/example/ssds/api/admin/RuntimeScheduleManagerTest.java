package com.example.ssds.api.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.admin.RuntimeSettingsService.RuntimeSchedulesChanged;
import com.example.ssds.api.admin.RuntimeSettingsService.ScheduleConfig;
import com.example.ssds.api.admin.RuntimeSettingsService.ScheduleItem;
import com.example.ssds.api.aitask.fullanalysis.FullAnalysisJob;
import com.example.ssds.api.calibration.CalibrationStatisticsJob;
import com.example.ssds.api.schedule.HeatCompositeCalibrationJob;
import com.example.ssds.api.schedule.RiskHeatAlertJob;
import com.example.ssds.api.scoring.PureScoringJob;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

class RuntimeScheduleManagerTest {

    private final TaskScheduler scheduler = mock(TaskScheduler.class);
    private final RuntimeSettingsService settings = mock(RuntimeSettingsService.class);
    private final ScheduledFuture<?> future = mock(ScheduledFuture.class);
    private final RuntimeScheduleManager manager = new RuntimeScheduleManager(
            scheduler,
            settings,
            mock(FullAnalysisJob.class),
            mock(PureScoringJob.class),
            mock(CalibrationStatisticsJob.class),
            mock(HeatCompositeCalibrationJob.class),
            mock(RiskHeatAlertJob.class));

    @Test
    void startRegistersOnlyEnabledKnownJobs() {
        ScheduleConfig config = new ScheduleConfig(List.of(
                new ScheduleItem("FULL_ANALYSIS", "weekly", "0 0 7 * * MON", true),
                new ScheduleItem("HEAT_ALERT", "alert", "0 30 6 * * *", false)));
        when(settings.schedules()).thenReturn(config);
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));

        manager.start();

        verify(scheduler).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    void settingsChangeCancelsOldRegistrationsAndAppliesNewConfig() {
        ScheduleConfig first = new ScheduleConfig(List.of(
                new ScheduleItem("FULL_ANALYSIS", "weekly", "0 0 7 * * MON", true)));
        ScheduleConfig second = new ScheduleConfig(List.of(
                new ScheduleItem("HEAT_ALERT", "alert", "0 30 6 * * *", true)));
        doReturn(future).when(scheduler).schedule(any(Runnable.class), any(Trigger.class));

        manager.reschedule(first);
        manager.settingsChanged(new RuntimeSchedulesChanged(second));

        verify(future).cancel(false);
        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Trigger.class));
    }
}
