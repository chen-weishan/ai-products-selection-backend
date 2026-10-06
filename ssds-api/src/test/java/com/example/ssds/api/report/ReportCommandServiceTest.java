package com.example.ssds.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.report.dto.ReportGenerateRequest;
import com.example.ssds.api.report.dto.ReportJobResponse;
import com.example.ssds.api.report.service.ReportAsyncCoordinator;
import com.example.ssds.api.report.service.ReportAuditService;
import com.example.ssds.api.report.service.ReportCommandService;
import com.example.ssds.api.report.service.ReportDataDao;
import com.example.ssds.api.report.service.ReportRequestValidator;
import com.example.ssds.core.domain.ReportFormat;
import com.example.ssds.core.domain.ReportType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.ReportJob;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.ReportJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportCommandServiceTest {
    @Mock private ReportRequestValidator validator;
    @Mock private ReportDataDao data;
    @Mock private ReportJobRepository jobs;
    @Mock private AppUserRepository users;
    @Mock private ReportAuditService audit;
    @Mock private ReportAsyncCoordinator async;

    @ParameterizedTest
    @ValueSource(ints = {60, 5000, 5001})
    void submitsEveryReportSizeToBackgroundExecutor(int estimatedRows) {
        Map<String, Object> params = Map.of("period", "2026W40");
        AppUser user = AppUser.builder().id(7L).email("buyer@example.com").build();
        AtomicReference<ReportJob> persisted = new AtomicReference<>();

        when(validator.validateAndNormalize(any())).thenReturn(params);
        when(users.findByEmail("buyer@example.com")).thenReturn(Optional.of(user));
        when(data.estimateRows(ReportType.WEEKLY_PICK, params)).thenReturn(estimatedRows);
        when(jobs.saveAndFlush(any())).thenAnswer(invocation -> {
            ReportJob job = invocation.getArgument(0);
            job.setId(11L);
            job.setRequestedAt(Instant.parse("2026-10-06T01:00:00Z"));
            persisted.set(job);
            return job;
        });
        when(jobs.findById(11L)).thenAnswer(ignored -> Optional.of(persisted.get()));

        ReportCommandService service = new ReportCommandService(
                validator, data, jobs, users, audit, async, new ObjectMapper());
        ReportJobResponse result = service.generate(
                new ReportGenerateRequest(ReportType.WEEKLY_PICK, ReportFormat.PDF, params),
                "buyer@example.com",
                "127.0.0.1");

        assertEquals(TaskStatus.PENDING, result.status());
        verify(async).submit(11L);
        verify(audit).generated(persisted.get(), user, "127.0.0.1");
    }
}
