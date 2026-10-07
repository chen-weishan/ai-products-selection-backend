package com.example.ssds.api.report.service;

import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.ReportJob;
import com.example.ssds.infra.repository.ReportJobRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportJobLifecycleService {
    private final ReportJobRepository jobs;
    private final ReportEventStreamService events;

    public ReportJobLifecycleService(
            ReportJobRepository jobs,
            ReportEventStreamService events) {
        this.jobs = jobs;
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReportJob start(long id) {
        ReportJob job = jobs.findById(id).orElseThrow();
        if (job.getStatus() == TaskStatus.SUCCEEDED || job.getStatus() == TaskStatus.FAILED) {
            return null;
        }
        job.setStatus(TaskStatus.RUNNING);
        ReportJob saved = jobs.saveAndFlush(job);
        events.publishAfterCommit(saved);
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(long id, String filePath, int rowCount) {
        ReportJob job = jobs.findById(id).orElseThrow();
        job.setFilePath(filePath);
        job.setRowCount(rowCount);
        job.setFinishedAt(Instant.now());
        job.setStatus(TaskStatus.SUCCEEDED);
        ReportJob saved = jobs.save(job);
        events.publishAfterCommit(saved);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(long id) {
        ReportJob job = jobs.findById(id).orElseThrow();
        job.setStatus(TaskStatus.FAILED);
        job.setFinishedAt(Instant.now());
        ReportJob saved = jobs.save(job);
        events.publishAfterCommit(saved);
    }
}
