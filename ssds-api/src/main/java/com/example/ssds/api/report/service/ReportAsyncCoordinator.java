package com.example.ssds.api.report.service;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ReportAsyncCoordinator {
    private final Executor executor;
    private final ReportJobWorker worker;

    public ReportAsyncCoordinator(
            @Qualifier("reportExecutor") Executor executor,
            ReportJobWorker worker) {
        this.executor = executor;
        this.worker = worker;
    }

    public void submit(long jobId) {
        executor.execute(() -> worker.generate(jobId));
    }
}
