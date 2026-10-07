package com.example.ssds.api.report.service;

import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.repository.ReportJobRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "ssds.report",
        name = "resume-on-startup",
        havingValue = "true",
        matchIfMissing = true)
public class ReportPendingResumeService {
    private final ReportJobRepository jobs;
    private final ReportAsyncCoordinator async;

    public ReportPendingResumeService(ReportJobRepository jobs, ReportAsyncCoordinator async) {
        this.jobs = jobs;
        this.async = async;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resume() {
        jobs.findByStatus(TaskStatus.PENDING).forEach(job -> async.submit(job.getId()));
        jobs.findByStatus(TaskStatus.RUNNING).forEach(job -> async.submit(job.getId()));
    }
}
