package com.example.ssds.api.imports.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.FieldError;
import com.example.ssds.api.imports.dto.ImportMappingTemplateRequest;
import com.example.ssds.api.imports.dto.ImportMappingTemplateResponse;
import com.example.ssds.core.domain.ImportDataType;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.ImportMappingTemplate;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.ImportMappingTemplateRepository;
import com.example.ssds.ingest.importer.ImportFieldRegistry;
import com.example.ssds.ingest.importer.ImportHeaderMapper;
import com.example.ssds.ingest.importer.ImportSystemField;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ImportMappingTemplateService {

    private static final ZoneId API_ZONE = ZoneId.of("Asia/Taipei");

    private final ImportMappingTemplateRepository repository;
    private final AppUserRepository appUserRepository;
    private final ImportFieldRegistry fieldRegistry;

    public ImportMappingTemplateService(
            ImportMappingTemplateRepository repository,
            AppUserRepository appUserRepository,
            ImportFieldRegistry fieldRegistry
    ) {
        this.repository = repository;
        this.appUserRepository = appUserRepository;
        this.fieldRegistry = fieldRegistry;
    }

    @Transactional(readOnly = true)
    public List<ImportMappingTemplateResponse> list(ImportDataType dataType, String actorEmail) {
        AppUser actor = findActor(actorEmail);
        return repository.findByCreatedByIdAndDataTypeOrderByUpdatedAtDesc(actor.getId(), dataType)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public ImportMappingTemplateResponse create(
            ImportMappingTemplateRequest request,
            String actorEmail
    ) {
        AppUser actor = findActor(actorEmail);
        String name = request.name().trim();
        if (repository.existsByCreatedByIdAndDataTypeAndNameIgnoreCase(
                actor.getId(), request.dataType(), name)) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "已有同名的欄位對應範本");
        }
        Map<String, String> mappings = validateMappings(request.dataType(), request.mappings());
        ImportMappingTemplate saved = repository.saveAndFlush(ImportMappingTemplate.builder()
                .name(name)
                .dataType(request.dataType())
                .mappings(mappings)
                .createdBy(actor)
                .build());
        return toResponse(saved);
    }

    public ImportMappingTemplateResponse update(
            Long id,
            ImportMappingTemplateRequest request,
            String actorEmail
    ) {
        AppUser actor = findActor(actorEmail);
        ImportMappingTemplate template = findOwned(id, actor.getId());
        String name = request.name().trim();
        if (repository.existsByCreatedByIdAndDataTypeAndNameIgnoreCaseAndIdNot(
                actor.getId(), request.dataType(), name, id)) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "已有同名的欄位對應範本");
        }
        template.setName(name);
        template.setDataType(request.dataType());
        template.setMappings(validateMappings(request.dataType(), request.mappings()));
        return toResponse(repository.saveAndFlush(template));
    }

    public void delete(Long id, String actorEmail) {
        AppUser actor = findActor(actorEmail);
        repository.delete(findOwned(id, actor.getId()));
    }

    private Map<String, String> validateMappings(
            ImportDataType dataType,
            Map<String, String> requestedMappings
    ) {
        List<ImportSystemField> fields = fieldRegistry.fieldsFor(dataType);
        Set<String> allowed = fields.stream().map(ImportSystemField::key)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> required = fields.stream().filter(ImportSystemField::required)
                .map(ImportSystemField::key)
                .collect(java.util.stream.Collectors.toSet());

        Map<String, String> normalized = new LinkedHashMap<>();
        Set<String> usedTargets = new LinkedHashSet<>();
        List<FieldError> errors = new java.util.ArrayList<>();
        requestedMappings.forEach((source, target) -> {
            String cleanSource = source.trim();
            String cleanTarget = target.trim();
            if (fieldRegistry.isPersonalDataHeader(ImportHeaderMapper.normalize(cleanSource))) {
                errors.add(new FieldError("mappings." + source, "可識別個資欄位不得匯入"));
            } else if (!allowed.contains(cleanTarget)) {
                errors.add(new FieldError("mappings." + source, "不是此資料類型可用的系統欄位"));
            } else if (!usedTargets.add(cleanTarget)) {
                errors.add(new FieldError("mappings." + source, "同一系統欄位不可重複對應"));
            } else {
                normalized.put(cleanSource, cleanTarget);
            }
        });
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(usedTargets);
        if (!missing.isEmpty()) {
            errors.add(new FieldError("mappings", "缺少必填系統欄位：" + String.join(", ", missing)));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "欄位對應驗證失敗", errors);
        }
        return normalized;
    }

    private AppUser findActor(String actorEmail) {
        return appUserRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.UNAUTHORIZED, "登入使用者不存在或已失效"));
    }

    private ImportMappingTemplate findOwned(Long id, Long actorId) {
        return repository.findByIdAndCreatedById(id, actorId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的欄位對應範本"));
    }

    private ImportMappingTemplateResponse toResponse(ImportMappingTemplate template) {
        return new ImportMappingTemplateResponse(
                template.getId(),
                template.getName(),
                template.getDataType(),
                Map.copyOf(template.getMappings()),
                OffsetDateTime.ofInstant(template.getCreatedAt(), API_ZONE),
                OffsetDateTime.ofInstant(template.getUpdatedAt(), API_ZONE));
    }
}
