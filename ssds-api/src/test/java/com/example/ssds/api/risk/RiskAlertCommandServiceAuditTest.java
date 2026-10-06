package com.example.ssds.api.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.core.domain.AlertStatus;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.RiskAlert;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.RiskAlertRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** AC-10-2：忽略示警須填理由，且理由要一併寫入稽核紀錄（不只存在示警本身）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskAlertCommandServiceAuditTest {

    private static final Long USER_ID = 100L;
    private static final Long ALERT_ID = 1L;

    @Mock
    private RiskAlertRepository alerts;

    @Mock
    private AppUserRepository users;

    @Mock
    private AuditLogRepository audits;

    private final ObjectMapper mapper = new ObjectMapper();
    @Mock
    private RiskImpactService impacts;

    private RiskAlertCommandService service;

    @BeforeEach
    void setUp() {
        service = new RiskAlertCommandService(alerts, users, audits, mapper, impacts);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(USER_ID, null));
        when(users.getReferenceById(USER_ID)).thenReturn(new AppUser());
        Product product = Product.builder()
                .id(12L)
                .name("測試品項")
                .category(Category.builder().id(3L).name("甜點").build())
                .build();
        RiskAlert alert = RiskAlert.builder()
                .id(ALERT_ID)
                .product(product)
                .riskType(RiskTypes.REVIEW_RISK)
                .severity(Severity.MEDIUM)
                .build();
        when(alerts.findById(ALERT_ID)).thenReturn(Optional.of(alert));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("忽略：稽核 afterJson 帶狀態與理由，含引號的理由仍是合法 JSON")
    void ignoreWritesReasonToAudit() throws Exception {
        service.ignore(ALERT_ID, "  已與供應商確認「缺貨」是 \"誤報\"  ");

        JsonNode after = capturedAfterJson();
        assertThat(after.get("status").asText()).isEqualTo("IGNORED");
        assertThat(after.get("reason").asText()).isEqualTo("已與供應商確認「缺貨」是 \"誤報\"");
    }

    @Test
    @DisplayName("確認：稽核 afterJson 只有狀態，不帶 reason")
    void acknowledgeHasNoReason() throws Exception {
        service.acknowledge(ALERT_ID);

        JsonNode after = capturedAfterJson();
        assertThat(after.get("status").asText()).isEqualTo(AlertStatus.ACKNOWLEDGED.name());
        assertThat(after.has("reason")).isFalse();
    }

    private JsonNode capturedAfterJson() throws Exception {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(audits).save(captor.capture());
        return mapper.readTree(captor.getValue().getAfterJson());
    }
}