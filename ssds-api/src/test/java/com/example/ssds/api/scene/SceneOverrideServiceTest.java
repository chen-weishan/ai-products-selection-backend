package com.example.ssds.api.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.scene.dto.SceneOverrideRequest;
import com.example.ssds.api.scoring.ScoreEvaluationService.EvaluationResult;
import com.example.ssds.api.scoring.ScoreExecutionService;
import com.example.ssds.api.scoring.ScoreExecutionService.EvaluationCommand;
import com.example.ssds.core.domain.LastScoringStatus;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.SceneClassificationLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SceneOverrideServiceTest {
    @Mock ProductRepository productRepository;
    @Mock SceneClassificationLogRepository sceneRepository;
    @Mock AppUserRepository appUserRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock ScoreExecutionService scoring;

    private SceneOverrideService service;

    @BeforeEach
    void setUp() {
        service = new SceneOverrideService(
                productRepository,
                sceneRepository,
                appUserRepository,
                auditLogRepository,
                scoring,
                new ObjectMapper());
    }

    @Test
    void retainsOriginalAiConfidenceForAuditButExcludesItFromOverrideScoring() {
        Product product = Product.builder().id(51L).trackType(TrackType.A).build();
        AppUser actor = AppUser.builder()
                .id(8L)
                .email("lead@ssds.dev")
                .displayName("採購主管")
                .build();
        SceneClassificationLog previous = SceneClassificationLog.builder()
                .id(70L)
                .product(product)
                .aiSceneType(SceneType.VIRAL)
                .aiConfidence(new BigDecimal("0.60"))
                .finalSceneType(SceneType.VIRAL)
                .heatBucket("HIGH")
                .period("2026W38")
                .signals(List.of("trend"))
                .build();
        when(productRepository.findWithDetailsById(51L)).thenReturn(Optional.of(product));
        when(appUserRepository.findByEmail("lead@ssds.dev")).thenReturn(Optional.of(actor));
        when(sceneRepository.findFirstByProductIdOrderByCreatedAtDesc(51L))
                .thenReturn(Optional.of(previous));
        when(sceneRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            SceneClassificationLog saved = invocation.getArgument(0);
            saved.setId(71L);
            saved.setCreatedAt(Instant.parse("2026-09-18T09:00:00Z"));
            return saved;
        });
        when(scoring.evaluate(any())).thenReturn(new EvaluationResult(
                LastScoringStatus.SCORED,
                "2026W38",
                3L,
                902L,
                List.of(),
                null));

        var response = service.override(
                51L,
                new SceneOverrideRequest(SceneType.FESTIVAL, "改用中秋檔期"),
                "lead@ssds.dev",
                "127.0.0.1");

        assertThat(response.scene().finalSceneType()).isEqualTo(SceneType.FESTIVAL);
        assertThat(response.scene().overrideReason()).isEqualTo("改用中秋檔期");
        assertThat(response.recalculation().primaryScoreId()).isEqualTo(902L);
        ArgumentCaptor<SceneClassificationLog> overrideLog =
                ArgumentCaptor.forClass(SceneClassificationLog.class);
        verify(sceneRepository).saveAndFlush(overrideLog.capture());
        assertThat(overrideLog.getValue().getAiConfidence()).isEqualByComparingTo("0.60");
        assertThat(overrideLog.getValue().getFinalSceneType()).isEqualTo(SceneType.FESTIVAL);
        assertThat(overrideLog.getValue().getOverriddenBy()).isSameAs(actor);
        ArgumentCaptor<EvaluationCommand> command = ArgumentCaptor.forClass(EvaluationCommand.class);
        verify(scoring).evaluate(command.capture());
        assertThat(command.getValue().primaryScene()).isEqualTo(SceneType.FESTIVAL);
        assertThat(command.getValue().alternativeScene()).isNull();
        assertThat(command.getValue().sceneConfidence()).isNull();
        assertThat(command.getValue().sceneFallbackApplied()).isFalse();
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("OVERRIDE");
        assertThat(audit.getValue().getAfterJson()).contains("改用中秋檔期", "902");
    }
}
