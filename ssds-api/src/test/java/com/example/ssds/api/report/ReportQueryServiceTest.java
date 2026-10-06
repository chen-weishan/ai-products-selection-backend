package com.example.ssds.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.report.config.ReportProperties;
import com.example.ssds.api.report.service.ReportAuditService;
import com.example.ssds.api.report.service.ReportFileStorage;
import com.example.ssds.api.report.service.ReportQueryService;
import com.example.ssds.core.domain.ReportFormat;
import com.example.ssds.core.domain.ReportType;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.ReportJob;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.ReportJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportQueryServiceTest {
    @TempDir Path temp;
    @Mock private ReportJobRepository jobs;
    @Mock private AppUserRepository users;
    @Mock private ReportAuditService audit;

    @Test
    void ownerCanDownloadAndDownloadIsAudited() throws Exception {
        AppUser owner = AppUser.builder().id(1L).email("owner@example.com").build();
        Path file = temp.resolve("weekly.pdf");
        Files.writeString(file, "report");
        ReportJob job = completedJob(10L, owner, file);
        ReportQueryService service = service();

        when(users.findByEmail(owner.getEmail())).thenReturn(Optional.of(owner));
        when(jobs.findByIdAndRequestedById(10L, 1L)).thenReturn(Optional.of(job));

        ReportQueryService.DownloadedReport result =
                service.download(10L, owner.getEmail(), "127.0.0.1");

        assertEquals(file, result.path());
        verify(audit).downloaded(job, owner, "127.0.0.1");
    }

    @Test
    void anotherUserCannotDownloadTheOwnersReport() throws Exception {
        AppUser intruder = AppUser.builder().id(2L).email("other@example.com").build();
        Path file = temp.resolve("weekly.pdf");
        Files.writeString(file, "report");
        ReportJob ownersJob = completedJob(
                10L, AppUser.builder().id(1L).email("owner@example.com").build(), file);
        ReportQueryService service = service();

        when(users.findByEmail(intruder.getEmail())).thenReturn(Optional.of(intruder));
        when(jobs.findByIdAndRequestedById(10L, 2L)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class,
                () -> service.download(ownersJob.getId(), intruder.getEmail(), "127.0.0.2"));
        verify(audit, never()).downloaded(ownersJob, intruder, "127.0.0.2");
    }

    private ReportQueryService service() {
        ReportProperties properties = new ReportProperties(temp.toString(), 5000, 90, "");
        return new ReportQueryService(
                jobs, users, new ReportFileStorage(properties), audit, new ObjectMapper());
    }

    private ReportJob completedJob(long id, AppUser owner, Path file) {
        return ReportJob.builder()
                .id(id)
                .reportType(ReportType.WEEKLY_PICK)
                .format(ReportFormat.PDF)
                .paramsJson("{}")
                .status(TaskStatus.SUCCEEDED)
                .filePath(file.toString())
                .requestedBy(owner)
                .build();
    }
}
