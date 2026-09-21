package com.example.ssds.api.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.scoring.PureScoringBatchService;
import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScoreCommandServiceTest {
    @Mock ProductRepository productRepository;
    @Mock AppUserRepository appUserRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock PureScoringBatchService scoring;

    private ScoreCommandService service;

    @BeforeEach
    void setUp() {
        service = new ScoreCommandService(
                productRepository,
                appUserRepository,
                auditLogRepository,
                scoring,
                new ObjectMapper());
    }

    @Test
    void recalculatesWithoutAiTaskAndWritesAuditLog() {
        Product product = Product.builder().id(41L).build();
        AppUser actor = AppUser.builder().id(7L).email("buyer@ssds.dev").build();
        when(productRepository.findById(41L)).thenReturn(Optional.of(product));
        when(appUserRepository.findByEmail("buyer@ssds.dev")).thenReturn(Optional.of(actor));
        when(scoring.evaluateProductIds(any(), any())).thenReturn(
                new PureScoringBatchService.BatchResult(
                        1,
                        1,
                        0,
                        List.of(new PureScoringBatchService.ItemResult(
                                41L, LastScoringStatus.SCORED, 901L, null)),
                        List.of()));

        var response = service.recalculate(41L, "buyer@ssds.dev", "127.0.0.1");

        assertThat(response.primaryScoreId()).isEqualTo(901L);
        assertThat(response.status()).isEqualTo(LastScoringStatus.SCORED);
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("RECALCULATE");
        assertThat(audit.getValue().getAfterJson()).contains("\"productId\":41");
    }
}
