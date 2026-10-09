package com.example.ssds.api.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssds.api.admin.AdminUserService.UserResponse;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.security.SecurityConfig;
import com.example.ssds.core.domain.RoleCode;
import com.example.ssds.core.domain.UserStatus;
import com.example.ssds.util.JwtUtils;
import com.example.ssds.util.UserDetailsServiceImpl;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * FR-13 controller 層：AC-13-3 僅 SYS_ADMIN、業務驗證錯誤轉成 400／409 而非 500。
 * 匯入 {@link SecurityConfig} 讓 {@code @PreAuthorize} 生效（見 CalibrationReportControllerTest 說明）。
 */
@WebMvcTest({ AdminUserController.class, RuntimeSettingsController.class })
@Import(SecurityConfig.class)
class AdminControllerTest {
    private static final String EMAIL = "sysadmin@ssds.dev";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminUserService users;
    @MockitoBean
    private RuntimeSettingsService settings;
    @MockitoBean
    private AiModelOptionsService modelOptions;
    @MockitoBean
    private JwtUtils jwtUtils;
    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    private static UserResponse sample() {
        return new UserResponse(1L, "buyer@ssds.dev", "採購", UserStatus.ACTIVE, List.of(RoleCode.BUYER),
                false, null, 0, null, null);
    }

    @ParameterizedTest(name = "{0} 不能進 /admin/users")
    @ValueSource(strings = { "BUYER", "BUYER_LEAD", "DATA_ADMIN", "VIEWER" })
    void nonAdminForbidden(String role) throws Exception {
        mockMvc.perform(get("/admin/users").with(user(EMAIL).roles(role)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/ai-config/options").with(user(EMAIL).roles(role)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(users, modelOptions);
    }

    @Test
    @DisplayName("SYS_ADMIN 可列出使用者")
    void adminLists() throws Exception {
        when(users.list()).thenReturn(List.of(sample()));

        mockMvc.perform(get("/admin/users").with(user(EMAIL).roles("SYS_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].email").value("buyer@ssds.dev"))
                .andExpect(jsonPath("$.data[0].roles[0]").value("BUYER"))
                .andExpect(jsonPath("$.data[0].passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("新增使用者把請求轉給 service")
    void adminCreates() throws Exception {
        when(users.create(any(), any())).thenReturn(sample());

        mockMvc.perform(post("/admin/users").with(user(EMAIL).roles("SYS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"buyer@ssds.dev\",\"displayName\":\"採購\",\"password\":\"Secret123\",\"roles\":[\"BUYER\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1));
        verify(users).create(eq(new AdminUserService.CreateUserRequest(
                "buyer@ssds.dev", "採購", "Secret123", List.of(RoleCode.BUYER))), anyString());
    }

    @Test
    @DisplayName("停用最後一位管理員 → 409 與訊息")
    void businessErrorBecomesConflict() throws Exception {
        when(users.changeStatus(eq(4L), eq(UserStatus.DISABLED), any()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "系統至少要保留一位啟用中的系統管理員"));

        mockMvc.perform(patch("/admin/users/4/status").with(user(EMAIL).roles("SYS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("系統至少要保留一位啟用中的系統管理員"));
    }

    @Test
    @DisplayName("AI 設定驗證失敗 → 400 而非 500")
    void aiConfigValidationIsBadRequest() throws Exception {
        when(settings.updateAi(any(), any()))
                .thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "三個預算池比例總和必須等於 1"));

        mockMvc.perform(put("/admin/ai-config").with(user(EMAIL).roles("SYS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"models":{"MODEL_CLASSIFY":{"primary":"m","fallbacks":[]}},
                                 "dailyQuota":1000,"trackAShare":0.5,"trackBShare":0.2,"retryShare":0.1,
                                 "warningRatio":0.8,"rateLimitPerMinute":20,"trendRateLimitPerMinute":5,
                                 "batchItemCap":150,"retryMax":3,"timeoutSeconds":30,"sourcingTimeoutSeconds":90,
                                 "cacheDays":6,"trendCacheDays":3,"sourcingCacheDays":3}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("三個預算池比例總和必須等於 1"));
    }

    @Test
    @DisplayName("模型選項端點回傳別名與模型")
    void modelOptions() throws Exception {
        when(modelOptions.options()).thenReturn(new AiModelOptionsService.AiConfigOptions(
                AiModelOptionsService.ALIASES,
                List.of(new AiModelOptionsService.ModelOption("mistral-small-latest", true, true)),
                "MISTRAL_API", null));

        mockMvc.perform(get("/admin/ai-config/options").with(user(EMAIL).roles("SYS_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aliases[0].code").value("MODEL_CLASSIFY"))
                .andExpect(jsonPath("$.data.aliases[0].label").value("情境判定"))
                .andExpect(jsonPath("$.data.models[0].id").value("mistral-small-latest"))
                .andExpect(jsonPath("$.data.source").value("MISTRAL_API"));
    }
}
