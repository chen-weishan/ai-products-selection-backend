package com.example.ssds.api.audit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.audit.dto.AuditLogResponse;
import com.example.ssds.api.audit.dto.AuditLogSearchRequest;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.repository.AuditLogRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

class AuditLogQueryServiceTest {

    private AuditLogRepository repository;
    private AuditLogQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditLogRepository.class);
        service = new AuditLogQueryService(repository);
    }

    @Test
    void returnsAuditPageWithTaipeiOffsetAndUserData() {
        Pageable pageable = PageRequest.of(
                0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        AppUser user = AppUser.builder()
                .id(7L)
                .email("lead@ssds.dev")
                .displayName("採購主管")
                .build();
        AuditLog log = AuditLog.builder()
                .id(10L)
                .user(user)
                .action("DELETE")
                .entityType("Product")
                .entityId(101L)
                .beforeJson("{\"deleted\":false}")
                .afterJson("{\"deleted\":true}")
                .createdAt(Instant.parse("2026-08-26T04:00:00Z"))
                .build();
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(java.util.List.of(log), pageable, 1));

        PageResponse<AuditLogResponse> response = service.search(
                new AuditLogSearchRequest(null, null, null, "DELETE", "Product"),
                pageable
        );

        assertEquals(1, response.totalElements());
        assertEquals("+08:00", response.content().getFirst()
                .createdAt().getOffset().toString());
        assertEquals("lead@ssds.dev", response.content().getFirst().userEmail());
        verify(repository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void rejectsInvertedDateRange() {
        Pageable pageable = PageRequest.of(
                0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        AuditLogSearchRequest request = new AuditLogSearchRequest(
                OffsetDateTime.parse("2026-08-27T00:00:00+08:00"),
                OffsetDateTime.parse("2026-08-26T00:00:00+08:00"),
                null, null, null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.search(request, pageable));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }
}
