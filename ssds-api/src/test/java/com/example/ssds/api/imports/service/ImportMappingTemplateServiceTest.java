package com.example.ssds.api.imports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.imports.dto.ImportMappingTemplateRequest;
import com.example.ssds.core.domain.ImportDataType;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.ImportMappingTemplate;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.ImportMappingTemplateRepository;
import com.example.ssds.ingest.importer.ImportFieldRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImportMappingTemplateServiceTest {

    private ImportMappingTemplateRepository repository;
    private ImportMappingTemplateService service;
    private AppUser actor;

    @BeforeEach
    void setUp() {
        repository = org.mockito.Mockito.mock(ImportMappingTemplateRepository.class);
        AppUserRepository users = org.mockito.Mockito.mock(AppUserRepository.class);
        service = new ImportMappingTemplateService(repository, users, new ImportFieldRegistry());
        actor = AppUser.builder().id(5L).email("data-admin@test.local").build();
        when(users.findByEmail(actor.getEmail())).thenReturn(Optional.of(actor));
    }

    @Test
    void createsReusableOwnedTemplateWithNormalizedMappings() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            ImportMappingTemplate template = invocation.getArgument(0);
            template.setId(9L);
            template.setCreatedAt(Instant.parse("2026-09-14T00:00:00Z"));
            template.setUpdatedAt(Instant.parse("2026-09-14T00:00:00Z"));
            return template;
        });
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put(" 訂單日期 ", "orderDate");
        mappings.put("品名", "productName");
        mappings.put("單價", "price");
        mappings.put("數量", "qty");

        var response = service.create(
                new ImportMappingTemplateRequest(" 銷售範本 ", ImportDataType.SALES, mappings),
                actor.getEmail());

        assertThat(response.id()).isEqualTo(9L);
        assertThat(response.name()).isEqualTo("銷售範本");
        assertThat(response.mappings()).containsKey("訂單日期");
    }

    @Test
    void rejectsPersonalDataAndTemplatesOwnedByAnotherUser() {
        var request = new ImportMappingTemplateRequest("危險範本", ImportDataType.SALES,
                Map.of("email", "productName", "日期", "orderDate", "單價", "price", "數量", "qty"));
        assertThatThrownBy(() -> service.create(request, actor.getEmail()))
                .isInstanceOf(BusinessException.class);

        when(repository.findByIdAndCreatedById(99L, actor.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(99L, actor.getEmail()))
                .isInstanceOf(BusinessException.class);
        verify(repository).findByIdAndCreatedById(99L, actor.getId());
    }
}
