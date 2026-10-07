package com.example.ssds.api.report.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.api.report.dto.ReportJobResponse;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.ReportJob;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.ReportJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportQueryService {
    private final ReportJobRepository jobs;
    private final AppUserRepository users;
    private final ReportFileStorage storage;
    private final ReportAuditService audit;
    private final ObjectMapper mapper;

    public ReportQueryService(
            ReportJobRepository jobs,
            AppUserRepository users,
            ReportFileStorage storage,
            ReportAuditService audit,
            ObjectMapper mapper) {
        this.jobs = jobs;
        this.users = users;
        this.storage = storage;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportJobResponse> list(String email, Pageable pageable) {
        AppUser user = user(email);
        return PageResponse.from(jobs.findByRequestedByIdOrderByRequestedAtDesc(
                user.getId(), pageable).map(job -> ReportJobResponse.from(job, mapper)));
    }

    public DownloadedReport download(long id, String email, String ip) {
        AppUser user = user(email);
        ReportJob job = jobs.findByIdAndRequestedById(id, user.getId())
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的報表"));
        if (job.getStatus() != TaskStatus.SUCCEEDED || job.getFilePath() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "報表尚未完成，無法下載");
        }
        Path file = storage.resolveStored(job.getFilePath());
        if (!Files.isRegularFile(file)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "報表檔案已不存在或已逾保存期限");
        }
        if (!Files.isReadable(file)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "報表檔案目前無法讀取");
        }
        audit.downloaded(job, user, ip);
        return new DownloadedReport(file.getFileName().toString(), job.getFormat().name(), file);
    }

    private AppUser user(String email) {
        return users.findByEmail(email).orElseThrow(() -> new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND, "找不到目前登入的使用者"));
    }

    public record DownloadedReport(String fileName, String format, Path path) {}
}
