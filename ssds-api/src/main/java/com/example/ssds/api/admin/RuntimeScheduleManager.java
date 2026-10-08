package com.example.ssds.api.admin;

import com.example.ssds.api.admin.RuntimeSettingsService.RuntimeSchedulesChanged;
import com.example.ssds.api.aitask.fullanalysis.FullAnalysisJob;
import com.example.ssds.api.calibration.CalibrationStatisticsJob;
import com.example.ssds.api.schedule.HeatCompositeCalibrationJob;
import com.example.ssds.api.schedule.RiskHeatAlertJob;
import com.example.ssds.api.scoring.PureScoringJob;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** S-14 可立即重新註冊的主要業務排程。 */
@Component
public class RuntimeScheduleManager {
    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");

    private final TaskScheduler scheduler;
    private final RuntimeSettingsService settings;
    private final Map<String, Runnable> jobs = new LinkedHashMap<>();
    private final List<ScheduledFuture<?>> scheduled = new ArrayList<>();

    public RuntimeScheduleManager(
            TaskScheduler scheduler,
            RuntimeSettingsService settings,
            FullAnalysisJob fullAnalysis,
            PureScoringJob scoring,
            CalibrationStatisticsJob calibration,
            HeatCompositeCalibrationJob heatComposite,
            RiskHeatAlertJob heatAlert) {
        this.scheduler = scheduler;
        this.settings = settings;
        jobs.put("FULL_ANALYSIS", fullAnalysis::startWeeklyAnalysis);
        jobs.put("FULL_ANALYSIS_RESUME", fullAnalysis::catchUpWeeklyItems);
        jobs.put("PURE_SCORING", scoring::scoreAllProducts);
        jobs.put("CALIBRATION", calibration::run);
        jobs.put("HEAT_COMPOSITE", heatComposite::run);
        jobs.put("HEAT_ALERT", heatAlert::run);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        reschedule(settings.schedules());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void settingsChanged(RuntimeSchedulesChanged event) {
        reschedule(event.config());
    }

    synchronized void reschedule(RuntimeSettingsService.ScheduleConfig config) {
        scheduled.forEach(future -> future.cancel(false));
        scheduled.clear();
        for (RuntimeSettingsService.ScheduleItem item : config.items()) {
            Runnable job = jobs.get(item.code());
            if (job == null || !item.enabled()) continue;
            ScheduledFuture<?> future = scheduler.schedule(job, new CronTrigger(item.cron(), ZONE));
            if (future != null) scheduled.add(future);
        }
    }
}
