package com.example.ssds.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.report.service.ReportEventStreamService;
import com.example.ssds.api.report.service.ReportJobLifecycleService;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.ReportJob;
import com.example.ssds.infra.repository.ReportJobRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportJobLifecycleServiceTest {
    @Mock private ReportJobRepository jobs;
    @Mock private ReportEventStreamService events;

    @Test
    void movesPendingJobThroughRunningToSucceeded() {
        ReportJob job = ReportJob.builder().id(1L).status(TaskStatus.PENDING).build();
        when(jobs.findById(1L)).thenReturn(Optional.of(job));
        when(jobs.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ReportJobLifecycleService service = new ReportJobLifecycleService(jobs, events);

        service.start(1L);
        assertEquals(TaskStatus.RUNNING, job.getStatus());
        verify(events).publishAfterCommit(job);

        service.succeed(1L, "report.pdf", 25);
        assertEquals(TaskStatus.SUCCEEDED, job.getStatus());
        assertEquals("report.pdf", job.getFilePath());
        assertEquals(25, job.getRowCount());
        assertNotNull(job.getFinishedAt());
    }

    @Test
    void movesRunningJobToFailedAndDoesNotRestartTerminalJobs() {
        ReportJob running = ReportJob.builder().id(2L).status(TaskStatus.RUNNING).build();
        when(jobs.findById(2L)).thenReturn(Optional.of(running));
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ReportJobLifecycleService service = new ReportJobLifecycleService(jobs, events);

        service.fail(2L);
        assertEquals(TaskStatus.FAILED, running.getStatus());
        assertNotNull(running.getFinishedAt());
        assertNull(service.start(2L));
    }
}
