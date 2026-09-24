package com.example.ssds.api.imports.controller;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.api.imports.dto.ImportBatchResponse;
import com.example.ssds.api.imports.dto.ImportConfirmRequest;
import com.example.ssds.api.imports.dto.ImportFieldResponse;
import com.example.ssds.api.imports.dto.ImportMappingTemplateRequest;
import com.example.ssds.api.imports.dto.ImportMappingTemplateResponse;
import com.example.ssds.api.imports.dto.ImportPreviewRequest;
import com.example.ssds.api.imports.dto.ImportPreviewResponse;
import com.example.ssds.api.imports.dto.ImportUploadResponse;
import com.example.ssds.api.imports.service.ImportMappingTemplateService;
import com.example.ssds.api.imports.service.ImportBatchQueryService;
import com.example.ssds.api.imports.service.ImportConfirmService;
import com.example.ssds.api.imports.service.ImportPreviewService;
import com.example.ssds.api.imports.service.ImportUploadService;
import com.example.ssds.api.imports.service.ImportPendingResumeService;
import com.example.ssds.core.domain.ImportDataType;
import com.example.ssds.ingest.importer.ImportFieldRegistry;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

/** FR-09 系統欄位契約與 AC-09-1 欄位對應範本 API。 */
@RestController
@RequestMapping("/imports")
@PreAuthorize("hasAnyRole('BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
public class ImportContractController {

    private final ImportFieldRegistry fieldRegistry;
    private final ImportMappingTemplateService templateService;
    private final ImportUploadService uploadService;
    private final ImportPreviewService previewService;
    private final ImportConfirmService confirmService;
    private final ImportBatchQueryService queryService;
    private final ImportPendingResumeService pendingResumeService;

    public ImportContractController(
            ImportFieldRegistry fieldRegistry,
            ImportMappingTemplateService templateService,
            ImportUploadService uploadService,
            ImportPreviewService previewService,
            ImportConfirmService confirmService,
            ImportBatchQueryService queryService,
            ImportPendingResumeService pendingResumeService
    ) {
        this.fieldRegistry = fieldRegistry;
        this.templateService = templateService;
        this.uploadService = uploadService;
        this.previewService = previewService;
        this.confirmService = confirmService;
        this.queryService = queryService;
        this.pendingResumeService = pendingResumeService;
    }

    @PostMapping("/upload")
    public ApiResponse<ImportUploadResponse> upload(
            @RequestParam(name = "dataType") ImportDataType dataType,
            @RequestPart(name = "file") MultipartFile file,
            Authentication authentication
    ) {
        return ApiResponse.success(uploadService.upload(
                dataType, file, authentication.getName()));
    }

    @PostMapping("/{batchId}/preview")
    public ApiResponse<ImportPreviewResponse> preview(
            @PathVariable(name = "batchId") Long batchId,
            @Valid @RequestBody ImportPreviewRequest request
    ) {
        return ApiResponse.success(previewService.preview(batchId, request));
    }

    @GetMapping("/{batchId}/resume")
    public ApiResponse<ImportUploadResponse> resumePending(
            @PathVariable(name = "batchId") Long batchId
    ) {
        return ApiResponse.success(pendingResumeService.resume(batchId));
    }

    /** Uses the current preview mapping; does not confirm or persist validation errors. */
    @PostMapping(value = "/{batchId}/preview/errors/download", produces = "text/csv")
    public ResponseEntity<byte[]> downloadPreviewErrors(
            @PathVariable(name = "batchId") Long batchId,
            @Valid @RequestBody ImportPreviewRequest request
    ) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("import-" + batchId + "-preview-errors.csv",
                                java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(previewService.errorCsv(batchId, request));
    }

    @PostMapping("/{batchId}/confirm")
    public ApiResponse<ImportBatchResponse> confirm(
            @PathVariable(name = "batchId") Long batchId,
            @Valid @RequestBody ImportConfirmRequest request
    ) {
        return ApiResponse.success(confirmService.confirm(batchId, request));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<PageResponse<ImportBatchResponse>> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable
    ) {
        return ApiResponse.success(queryService.list(pageable));
    }

    @GetMapping("/{batchId}")
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<ImportBatchResponse> get(
            @PathVariable(name = "batchId") Long batchId
    ) {
        return ApiResponse.success(queryService.get(batchId));
    }

    @GetMapping("/{batchId}/errors/download")
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ResponseEntity<byte[]> downloadErrors(
            @PathVariable(name = "batchId") Long batchId
    ) {
        byte[] content = queryService.errorCsv(batchId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("import-" + batchId + "-errors.csv", java.nio.charset.StandardCharsets.UTF_8)
                        .build().toString())
                .body(content);
    }

    @PostMapping("/{batchId}/recalculation/retry")
    public ApiResponse<Integer> retryRecalculation(@PathVariable(name="batchId") Long batchId) {
        return ApiResponse.success(queryService.retryRecalculation(batchId));
    }

    @GetMapping("/{batchId}/unprocessed/download")
    public ResponseEntity<byte[]> downloadUnprocessed(@PathVariable(name="batchId") Long batchId) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=import-"+batchId+"-unprocessed.csv")
                .body(queryService.unprocessedCsv(batchId));
    }

    @GetMapping("/fields")
    public ApiResponse<List<ImportFieldResponse>> fields(
            @RequestParam(name = "dataType") ImportDataType dataType
    ) {
        return ApiResponse.success(fieldRegistry.fieldsFor(dataType).stream()
                .map(ImportFieldResponse::from)
                .toList());
    }

    @GetMapping("/mapping-templates")
    public ApiResponse<List<ImportMappingTemplateResponse>> templates(
            @RequestParam(name = "dataType") ImportDataType dataType,
            Authentication authentication
    ) {
        return ApiResponse.success(templateService.list(dataType, authentication.getName()));
    }

    @PostMapping("/mapping-templates")
    public ApiResponse<ImportMappingTemplateResponse> createTemplate(
            @Valid @RequestBody ImportMappingTemplateRequest request,
            Authentication authentication
    ) {
        return ApiResponse.success(templateService.create(request, authentication.getName()));
    }

    @PutMapping("/mapping-templates/{id}")
    public ApiResponse<ImportMappingTemplateResponse> updateTemplate(
            @PathVariable(name = "id") Long id,
            @Valid @RequestBody ImportMappingTemplateRequest request,
            Authentication authentication
    ) {
        return ApiResponse.success(templateService.update(id, request, authentication.getName()));
    }

    @DeleteMapping("/mapping-templates/{id}")
    public ApiResponse<Void> deleteTemplate(
            @PathVariable(name = "id") Long id,
            Authentication authentication
    ) {
        templateService.delete(id, authentication.getName());
        return ApiResponse.success(null);
    }
}
