package com.example.ssds.api.report;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.ssds.api.report.service.ReportAsyncCoordinator;
import com.example.ssds.api.report.service.ReportPendingResumeService;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.ReportJob;
import com.example.ssds.infra.repository.ReportJobRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportPendingResumeServiceTest {
    @Mock private ReportJobRepository jobs;
    @Mock private ReportAsyncCoordinator async;

    @Test
    void resubmitsPendingAndInterruptedRunningJobsAfterRestart() {
        when(jobs.findByStatus(TaskStatus.PENDING)).thenReturn(List.of(job(1L)));
        when(jobs.findByStatus(TaskStatus.RUNNING)).thenReturn(List.of(job(2L)));

        new ReportPendingResumeService(jobs, async).resume();

        verify(async).submit(1L);
        verify(async).submit(2L);
        verifyNoMoreInteractions(async);
    }

    private ReportJob job(long id) {
        return ReportJob.builder().id(id).build();
    }
}
