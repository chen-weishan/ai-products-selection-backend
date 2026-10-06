package com.example.ssds.api.report.service;

import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.ReportJob;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ReportAuditService {
    private final AuditLogRepository auditLogs;
    private final ObjectMapper mapper;

    public ReportAuditService(AuditLogRepository auditLogs, ObjectMapper mapper) {
        this.auditLogs = auditLogs;
        this.mapper = mapper;
    }

    public void generated(ReportJob job, AppUser user, String ip) {
        save("REPORT_GENERATE", job, user, ip, Map.of(
                "reportType", job.getReportType().name(),
                "format", job.getFormat().name(),
                "params", job.getParamsJson()));
    }

    public void downloaded(ReportJob job, AppUser user, String ip) {
        save("REPORT_DOWNLOAD", job, user, ip, Map.of(
                "reportType", job.getReportType().name(),
                "format", job.getFormat().name(),
                "fileName", java.nio.file.Path.of(job.getFilePath()).getFileName().toString()));
    }

    private void save(
            String action, ReportJob job, AppUser user, String ip, Map<String, Object> details) {
        auditLogs.save(AuditLog.builder()
                .user(user)
                .action(action)
                .entityType("REPORT_JOB")
                .entityId(job.getId())
                .afterJson(json(details))
                .ip(ip)
                .build());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("無法序列化報表稽核內容", exception);
        }
    }
}
