package com.example.ssds.api.report.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.report.dto.ReportGenerateRequest;
import com.example.ssds.api.report.dto.ReportJobResponse;
import com.example.ssds.core.domain.TaskStatus;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.ReportJob;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.ReportJobRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ReportCommandService {
    private final ReportRequestValidator validator;
    private final ReportDataDao data;
    private final ReportJobRepository jobs;
    private final AppUserRepository users;
    private final ReportAuditService audit;
    private final ReportAsyncCoordinator async;
    private final ObjectMapper mapper;

    public ReportCommandService(
            ReportRequestValidator validator,
            ReportDataDao data,
            ReportJobRepository jobs,
            AppUserRepository users,
            ReportAuditService audit,
            ReportAsyncCoordinator async,
            ObjectMapper mapper) {
        this.validator = validator;
        this.data = data;
        this.jobs = jobs;
        this.users = users;
        this.audit = audit;
        this.async = async;
        this.mapper = mapper;
    }

    public ReportJobResponse generate(
            ReportGenerateRequest request, String requesterEmail, String ip) {
        Map<String, Object> params = validator.validateAndNormalize(request);
        AppUser user = users.findByEmail(requesterEmail)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到目前登入的使用者"));
        int estimatedRows = data.estimateRows(request.reportType(), params);
        ReportJob job = jobs.saveAndFlush(ReportJob.builder()
                .reportType(request.reportType())
                .format(request.format())
                .paramsJson(json(params))
                .status(TaskStatus.PENDING)
                .rowCount(estimatedRows)
                .requestedBy(user)
                .build());
        audit.generated(job, user, ip);
        async.submit(job.getId());
        return ReportJobResponse.from(jobs.findById(job.getId()).orElseThrow(), mapper);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("無法序列化報表條件", exception);
        }
    }
}
