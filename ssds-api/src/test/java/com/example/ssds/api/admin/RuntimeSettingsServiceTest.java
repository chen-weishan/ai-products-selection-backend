package com.example.ssds.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.ai.budget.DailyAiBudget;
import com.example.ssds.ai.config.MistralModelCatalog;
import com.example.ssds.ai.resilience.GlobalAiRateLimiter;
import com.example.ssds.api.admin.RuntimeSettingsService.AiConfig;
import com.example.ssds.api.admin.RuntimeSettingsService.ModelRoute;
import com.example.ssds.api.admin.RuntimeSettingsService.ScheduleConfig;
import com.example.ssds.api.admin.RuntimeSettingsService.ScheduleItem;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.RuntimeSetting;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.RuntimeSettingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** S-14 執行期設定：驗證錯誤要回 VALIDATION_FAILED（前端才看得到訊息）、別名固定順序、排程名稱以伺服器為準。 */
class RuntimeSettingsServiceTest {
    private final RuntimeSettingRepository repository = mock(RuntimeSettingRepository.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private RuntimeSettingsService service;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(4L, null, List.of()));
        when(repository.findById(any())).thenReturn(Optional.empty());
        when(users.getReferenceById(any())).thenReturn(AppUser.builder().id(4L).build());
        service = new RuntimeSettingsService(repository, audits, users, mapper,
                mock(MistralModelCatalog.class), mock(DailyAiBudget.class), mock(GlobalAiRateLimiter.class),
                mock(ApplicationEventPublisher.class), List.of(), List.of(),
                "classify-a", "classify-b", "long-a", "long-b", "short-a", "short-b",
                "num-a", "num-b", "reason-a", "reason-b",
                1000, 0.7, 0.2, 0.1, 20, 5, 150, 3, 30, 90, 6, 3, 3,
                true, "0 0 7 * * MON", "0 0 7 * * TUE-SUN", false, "0 50 6 * * MON",
                true, "0 0 8,9 1 1,4,7,10 *", true, "0 30 6 * * *", "0 0 6 * * *",
                5, Duration.ofMinutes(15), 14, 30, 10, new BigDecimal("0.5"), new BigDecimal("0.7"), 200);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static AiConfig withModels(Map<String, ModelRoute> models) {
        return new AiConfig(models, 1000, 0.7, 0.2, 0.1, 0.8, 20, 5, 150, 3, 30, 90, 6, 3, 3);
    }

    private static void assertValidationFailed(Runnable action, String message) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(message)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("預設 AI 設定的別名依 §6.7.2 順序輸出")
    void aliasesInSpecOrder() {
        assertThat(service.aiConfig().models().keySet()).containsExactly(
                "MODEL_CLASSIFY", "MODEL_LONG_TEXT", "MODEL_SHORT_GEN", "MODEL_NUMERIC", "MODEL_REASONING");
    }

    @Test
    @DisplayName("亂序傳入的別名也會排回固定順序；備援去重並移除與主模型相同者")
    void normalizesRoutes() {
        Map<String, ModelRoute> shuffled = new LinkedHashMap<>();
        shuffled.put("MODEL_REASONING", new ModelRoute(" r1 ", List.of("r1", " r2", "r2", "")));
        shuffled.put("MODEL_CLASSIFY", new ModelRoute("c1", null));
        AiConfig config = withModels(shuffled);

        assertThat(config.models().keySet()).containsExactly("MODEL_CLASSIFY", "MODEL_REASONING");
        assertThat(config.models().get("MODEL_REASONING")).isEqualTo(new ModelRoute("r1", List.of("r2")));
    }

    @Test
    @DisplayName("比例總和不為 1 → VALIDATION_FAILED，不寫入")
    void shareSumValidated() {
        AiConfig current = service.aiConfig();
        AiConfig bad = new AiConfig(current.models(), 1000, 0.5, 0.2, 0.1, 0.8, 20, 5, 150, 3, 30, 90, 6, 3, 3);

        assertValidationFailed(() -> service.updateAi(bad, null), "三個預算池比例總和必須等於 1");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("模型名稱含空白或逗號 → VALIDATION_FAILED")
    void modelIdValidated() {
        Map<String, ModelRoute> models = new LinkedHashMap<>(service.aiConfig().models());
        models.put("MODEL_NUMERIC", new ModelRoute("bad model", List.of()));

        assertValidationFailed(() -> service.updateAi(withModels(models), null), "模型名稱格式不正確");
    }

    @Test
    @DisplayName("cron 格式錯誤 → VALIDATION_FAILED（原本落到 500）")
    void invalidCron() {
        List<ScheduleItem> items = service.schedules().items().stream()
                .map(item -> item.code().equals("PURE_SCORING")
                        ? new ScheduleItem(item.code(), item.label(), "every monday", item.enabled()) : item)
                .toList();

        assertValidationFailed(() -> service.updateSchedules(new ScheduleConfig(items), null), "每週全量評分 的排程格式不正確");
    }

    @Test
    @DisplayName("排程名稱以伺服器定義為準，cron 去空白，並寫稽核")
    void scheduleLabelsFromServer() throws Exception {
        List<ScheduleItem> items = service.schedules().items().stream()
                .map(item -> new ScheduleItem(item.code(), "被竄改", " " + item.cron() + " ", item.enabled()))
                .toList();

        ScheduleConfig saved = service.updateSchedules(new ScheduleConfig(items), null);

        assertThat(saved.items()).extracting(ScheduleItem::label).doesNotContain("被竄改").contains("每週全量評分");
        assertThat(saved.items()).extracting(ScheduleItem::cron).allMatch(cron -> cron.equals(cron.trim()));
        ArgumentCaptor<RuntimeSetting> captor = ArgumentCaptor.forClass(RuntimeSetting.class);
        verify(repository).save(captor.capture());
        assertThat(mapper.readValue(captor.getValue().getValueJson(), ScheduleConfig.class)).isEqualTo(saved);
        verify(audits).save(any());
    }
}
