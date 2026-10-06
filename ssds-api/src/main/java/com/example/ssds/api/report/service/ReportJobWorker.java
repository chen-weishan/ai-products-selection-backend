package com.example.ssds.api.report.service;

import com.example.ssds.api.report.model.ReportDataset;
import com.example.ssds.core.domain.ReportFormat;
import com.example.ssds.infra.entity.ReportJob;
import com.example.ssds.infra.repository.ReportJobRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ReportJobWorker {
    private static final Logger log = LoggerFactory.getLogger(ReportJobWorker.class);
    private final ReportJobLifecycleService lifecycle;
    private final ReportJobRepository jobs;
    private final ReportDataDao data;
    private final ReportFileStorage storage;
    private final XlsxReportWriter xlsx;
    private final PdfReportWriter pdf;
    private final ObjectMapper mapper;

    public ReportJobWorker(
            ReportJobLifecycleService lifecycle,
            ReportJobRepository jobs,
            ReportDataDao data,
            ReportFileStorage storage,
            XlsxReportWriter xlsx,
            PdfReportWriter pdf,
            ObjectMapper mapper) {
        this.lifecycle = lifecycle;
        this.jobs = jobs;
        this.data = data;
        this.storage = storage;
        this.xlsx = xlsx;
        this.pdf = pdf;
        this.mapper = mapper;
    }

    public void generate(long jobId) {
        Path target = null;
        try {
            ReportJob claimed = lifecycle.start(jobId);
            if (claimed == null) {
                return;
            }
            ReportJob job = jobs.findById(jobId).orElseThrow();
            Map<String, Object> params = mapper.readValue(
                    job.getParamsJson(), new TypeReference<>() {});
            ReportDataset dataset = data.load(job.getReportType(), params);
            target = storage.target(jobId, job.getReportType(), job.getFormat(), params);
            if (job.getFormat() == ReportFormat.XLSX) {
                xlsx.write(dataset, target);
            } else {
                pdf.write(dataset, target);
            }
            lifecycle.succeed(jobId, target.toString(), dataset.rowCount());
            log.info("FR12 report completed: jobId={}, type={}, rows={}",
                    jobId, job.getReportType(), dataset.rowCount());
        } catch (Exception exception) {
            if (target != null) {
                try {
                    Files.deleteIfExists(target);
                } catch (Exception deleteFailure) {
                    log.warn("Failed to remove partial report: {}", target, deleteFailure);
                }
            }
            lifecycle.fail(jobId);
            log.error("FR12 report failed: jobId={}", jobId, exception);
        }
    }
}
