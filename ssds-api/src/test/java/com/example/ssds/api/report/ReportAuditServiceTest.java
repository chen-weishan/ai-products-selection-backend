package com.example.ssds.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.ssds.api.report.service.ReportAuditService;
import com.example.ssds.core.domain.ReportFormat;
import com.example.ssds.core.domain.ReportType;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.ReportJob;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportAuditServiceTest {
    @Mock private AuditLogRepository auditLogs;

    @Test
    void recordsBothGenerationAndDownloadActions() {
        AppUser user = AppUser.builder().id(3L).email("lead@example.com").build();
        ReportJob job = ReportJob.builder()
                .id(20L)
                .reportType(ReportType.SCORE_DETAIL)
                .format(ReportFormat.XLSX)
                .paramsJson("{\"period\":\"2026W40\"}")
                .filePath(Path.of("reports", "score.xlsx").toString())
                .requestedBy(user)
                .build();
        ReportAuditService service = new ReportAuditService(auditLogs, new ObjectMapper());

        service.generated(job, user, "10.0.0.1");
        service.downloaded(job, user, "10.0.0.2");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogs, times(2)).save(captor.capture());
        assertEquals("REPORT_GENERATE", captor.getAllValues().get(0).getAction());
        assertEquals("REPORT_DOWNLOAD", captor.getAllValues().get(1).getAction());
        assertEquals("REPORT_JOB", captor.getAllValues().get(1).getEntityType());
        assertEquals(20L, captor.getAllValues().get(1).getEntityId());
        assertTrue(captor.getAllValues().get(1).getAfterJson().contains("score.xlsx"));
    }
}
