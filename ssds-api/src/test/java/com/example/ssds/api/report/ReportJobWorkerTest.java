package com.example.ssds.api.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.report.model.ReportDataset;
import com.example.ssds.api.report.service.PdfReportWriter;
import com.example.ssds.api.report.service.ReportDataDao;
import com.example.ssds.api.report.service.ReportFileStorage;
import com.example.ssds.api.report.service.ReportJobLifecycleService;
import com.example.ssds.api.report.service.ReportJobWorker;
import com.example.ssds.api.report.service.XlsxReportWriter;
import com.example.ssds.core.domain.ReportFormat;
import com.example.ssds.core.domain.ReportType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.ReportJob;
import com.example.ssds.infra.repository.ReportJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportJobWorkerTest {
    @TempDir Path temp;
    @Mock private ReportJobLifecycleService lifecycle;
    @Mock private ReportJobRepository jobs;
    @Mock private ReportDataDao data;
    @Mock private ReportFileStorage storage;
    @Mock private XlsxReportWriter xlsx;
    @Mock private PdfReportWriter pdf;

    @Test
    void marksSuccessfulGenerationAsSucceeded() {
        ReportJob job = job(1L, ReportFormat.PDF);
        ReportDataset dataset = new ReportDataset("weekly", "period", List.of(), 42);
        Path target = temp.resolve("weekly.pdf");
        when(lifecycle.start(1L)).thenReturn(job);
        when(jobs.findById(1L)).thenReturn(Optional.of(job));
        when(data.load(ReportType.WEEKLY_PICK, Map.of("period", "2026W40"))).thenReturn(dataset);
        when(storage.target(eq(1L), eq(ReportType.WEEKLY_PICK), eq(ReportFormat.PDF), any()))
                .thenReturn(target);

        worker().generate(1L);

        verify(pdf).write(dataset, target);
        verify(lifecycle).succeed(1L, target.toString(), 42);
        verify(lifecycle, never()).fail(1L);
    }

    @Test
    void removesPartialFileAndMarksGenerationAsFailed() throws Exception {
        ReportJob job = job(2L, ReportFormat.PDF);
        ReportDataset dataset = new ReportDataset("weekly", "period", List.of(), 42);
        Path target = temp.resolve("partial.pdf");
        Files.writeString(target, "partial");
        when(lifecycle.start(2L)).thenReturn(job);
        when(jobs.findById(2L)).thenReturn(Optional.of(job));
        when(data.load(ReportType.WEEKLY_PICK, Map.of("period", "2026W40"))).thenReturn(dataset);
        when(storage.target(eq(2L), eq(ReportType.WEEKLY_PICK), eq(ReportFormat.PDF), any()))
                .thenReturn(target);
        doThrow(new IllegalStateException("writer failed")).when(pdf).write(dataset, target);

        worker().generate(2L);

        assertFalse(Files.exists(target));
        verify(lifecycle).fail(2L);
        verify(lifecycle, never()).succeed(anyLong(), anyString(), anyInt());
    }

    private ReportJobWorker worker() {
        return new ReportJobWorker(
                lifecycle, jobs, data, storage, xlsx, pdf, new ObjectMapper());
    }

    private ReportJob job(long id, ReportFormat format) {
        return ReportJob.builder()
                .id(id)
                .reportType(ReportType.WEEKLY_PICK)
                .format(format)
                .paramsJson("{\"period\":\"2026W40\"}")
                .status(TaskStatus.PENDING)
                .build();
    }
}
