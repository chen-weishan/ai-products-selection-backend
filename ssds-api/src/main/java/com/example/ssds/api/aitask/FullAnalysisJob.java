package com.example.ssds.api.aitask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每週全量分析與隔日配額續跑。 */
@Component
@ConditionalOnProperty(name = "ai.full-analysis.schedule-enabled", havingValue = "true")
public class FullAnalysisJob {
    private static final Logger log = LoggerFactory.getLogger(FullAnalysisJob.class);
    private final AiTaskService tasks;

    public FullAnalysisJob(AiTaskService tasks) {
        this.tasks = tasks;
    }

    @Scheduled(cron = "${ai.full-analysis.schedule-cron:0 0 7 * * MON}", zone = "Asia/Taipei")
    public void startWeeklyAnalysis() {
        tasks.createScheduledFullAnalysis().ifPresentOrElse(
                task -> log.info("Weekly FULL_ANALYSIS created: taskId={}, items={}",
                        task.taskId(), task.totalCount()),
                () -> log.info("Weekly FULL_ANALYSIS skipped: no eligible item or active task exists"));
    }

    @Scheduled(cron = "${ai.full-analysis.resume-cron:0 0 7 * * TUE-SUN}", zone = "Asia/Taipei")
    public void resumeQuotaSkippedItems() {
        tasks.resumeQuotaSkippedFullAnalysis().ifPresentOrElse(
                task -> log.info("FULL_ANALYSIS quota continuation created: taskId={}, items={}",
                        task.taskId(), task.totalCount()),
                () -> log.debug("FULL_ANALYSIS quota continuation skipped"));
    }
}
