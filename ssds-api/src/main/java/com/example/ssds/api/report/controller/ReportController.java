package com.example.ssds.api.report.controller;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.api.report.dto.ReportGenerateRequest;
import com.example.ssds.api.report.dto.ReportFilterOptionsResponse;
import com.example.ssds.api.report.dto.ReportJobResponse;
import com.example.ssds.api.report.service.ReportCommandService;
import com.example.ssds.api.report.service.ReportEventStreamService;
import com.example.ssds.api.report.service.ReportFilterOptionsService;
import com.example.ssds.api.report.service.ReportQueryService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/reports")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Reports", description = "FR12 報表產生、查詢與下載")
public class ReportController {
    private final ReportCommandService commands;
    private final ReportQueryService queries;
    private final ReportFilterOptionsService filterOptions;
    private final ReportEventStreamService events;

    public ReportController(
            ReportCommandService commands,
            ReportQueryService queries,
            ReportFilterOptionsService filterOptions,
            ReportEventStreamService events) {
        this.commands = commands;
        this.queries = queries;
        this.filterOptions = filterOptions;
        this.events = events;
    }

    @PostMapping(value = "/generate", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "generateReport", summary = "建立報表產生任務")
    public ApiResponse<ReportJobResponse> generate(
            @Valid @RequestBody ReportGenerateRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(commands.generate(
                request, authentication.getName(), httpRequest.getRemoteAddr()));
    }

    @GetMapping(value = "/filter-options", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getReportFilterOptions", summary = "取得報表條件選項")
    public ApiResponse<ReportFilterOptionsResponse> filterOptions() {
        return ApiResponse.success(filterOptions.get());
    }

    @Hidden
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(Authentication authentication) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .header("X-Accel-Buffering", "no")
                .body(events.subscribe(authentication.getName()));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listReports", summary = "查詢目前使用者的報表任務")
    public ApiResponse<PageResponse<ReportJobResponse>> list(
            Authentication authentication,
            @ParameterObject
            @PageableDefault(size = 20, sort = "requestedAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ApiResponse.success(queries.list(authentication.getName(), pageable));
    }

    @GetMapping("/{id}/download")
    @Operation(operationId = "downloadReport", summary = "下載已完成的報表")
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "PDF 或 XLSX 報表檔案",
            content = {
                    @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                            schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType =
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            schema = @Schema(type = "string", format = "binary"))
            }))
    public ResponseEntity<Resource> download(
            @PathVariable long id,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        ReportQueryService.DownloadedReport report = queries.download(
                id, authentication.getName(), httpRequest.getRemoteAddr());
        MediaType type = "PDF".equals(report.format())
                ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        Resource resource = new FileSystemResource(report.path());
        long size;
        try {
            size = resource.contentLength();
        } catch (IOException exception) {
            throw new IllegalStateException("讀取報表檔案大小失敗", exception);
        }
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(report.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentLength(size)
                .body(resource);
    }
}
