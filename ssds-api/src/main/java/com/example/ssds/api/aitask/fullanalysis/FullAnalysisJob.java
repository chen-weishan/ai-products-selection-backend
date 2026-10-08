package com.example.ssds.api.aitask.fullanalysis;

import com.example.ssds.api.aitask.service.AiTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 每週全量分析與隔日配額續跑。 */
@Component
public class FullAnalysisJob {
    private static final Logger log = LoggerFactory.getLogger(FullAnalysisJob.class);
    private final AiTaskService tasks;

    public FullAnalysisJob(AiTaskService tasks) {
        this.tasks = tasks;
    }

    public void startWeeklyAnalysis() {
        tasks.createScheduledFullAnalysis().ifPresentOrElse(
                task -> log.info("Weekly FULL_ANALYSIS created: taskId={}, items={}",
                        task.taskId(), task.totalCount()),
                () -> log.info("Weekly FULL_ANALYSIS skipped: no eligible item or active task exists"));
    }

    public void catchUpWeeklyItems() {
        tasks.createFullAnalysisCatchUp().ifPresentOrElse(
                task -> log.info("FULL_ANALYSIS weekly catch-up created: taskId={}, items={}",
                        task.taskId(), task.totalCount()),
                () -> log.debug("FULL_ANALYSIS weekly catch-up skipped"));
    }
}
