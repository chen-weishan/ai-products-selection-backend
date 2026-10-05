package com.example.ssds.api.decision;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssds.api.decision.dto.CreateDecisionRequest;
import com.example.ssds.api.security.SecurityConfig;
import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.util.JwtUtils;
import com.example.ssds.util.UserDetailsServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * FR-11 Controller 層：§2.1 角色權限矩陣（{@code @PreAuthorize}）與 DTO 驗證錯誤的 HTTP 轉換。
 * 業務規則由 {@link DecisionCommandServiceTest} 等服務測試涵蓋，這裡 service 一律 mock。
 *
 * <p>匯入 {@link SecurityConfig} 是為了 {@code @EnableMethodSecurity}：
 * {@code @WebMvcTest} 不掃 {@code @Configuration}，少了它 {@code @PreAuthorize} 不生效，403 測試會假通過。
 */
@WebMvcTest(DecisionController.class)
@Import(SecurityConfig.class)
class DecisionControllerTest {

    private static final String BUYER_EMAIL = "buyer@ssds.dev";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DecisionCommandService commandService;
    @MockitoBean
    private DecisionQueryService queryService;
    @MockitoBean
    private DecisionAccuracyService accuracyService;
    // JwtAuthFilter 是 @Component Filter，@WebMvcTest 會載入它，其相依須補上
    @MockitoBean
    private JwtUtils jwtUtils;
    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    private static MockHttpServletRequestBuilder postJson(String url, String body) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Nested
    @DisplayName("POST /products/{id}/decisions（§2.1 權限列 11：BUYER、BUYER_LEAD、SYS_ADMIN）")
    class Create {

        private static final String WATCH_BODY = "{\"decision\":\"WATCH\",\"reason\":\"再觀察兩週\"}";

        @ParameterizedTest(name = "{0} 可建立決策")
        @ValueSource(strings = { "BUYER", "BUYER_LEAD", "SYS_ADMIN" })
        void allowedRolesCreate(String role) throws Exception {
            when(commandService.create(eq(1L), any(CreateDecisionRequest.class), anyString(), any()))
                    .thenReturn(DecisionMapper.toResponse(
                            DecisionFixtures.decision(99L, DecisionType.WATCH), DecisionFixtures.TODAY));

            mockMvc.perform(postJson("/products/1/decisions", WATCH_BODY)
                            .with(user(BUYER_EMAIL).roles(role)))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/decisions/99"))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(99));

            verify(commandService).create(eq(1L), any(CreateDecisionRequest.class), eq(BUYER_EMAIL), any());
        }

        @ParameterizedTest(name = "{0} 回 403 FORBIDDEN，不進 service")
        @ValueSource(strings = { "DATA_ADMIN", "VIEWER" })
        void otherRolesForbidden(String role) throws Exception {
            mockMvc.perform(postJson("/products/1/decisions", WATCH_BODY)
                            .with(user(BUYER_EMAIL).roles(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

            verifyNoInteractions(commandService);
        }

        @Test
        @DisplayName("缺 decision 回 400 VALIDATION_FAILED，fieldErrors 指向 decision")
        void missingDecisionIsValidationError() throws Exception {
            mockMvc.perform(postJson("/products/1/decisions", "{\"reason\":\"再觀察兩週\"}")
                            .with(user(BUYER_EMAIL).roles("BUYER")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.fieldErrors[0].field").value("decision"));

            verifyNoInteractions(commandService);
        }

        @Test
        @DisplayName("decision 不是 WATCH／ADOPT／REJECT 回 400，不是 500")
        void unknownDecisionIsBadRequest() throws Exception {
            mockMvc.perform(postJson("/products/1/decisions", "{\"decision\":\"MAYBE\"}")
                            .with(user(BUYER_EMAIL).roles("BUYER")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

            verifyNoInteractions(commandService);
        }
    }

    @Nested
    @DisplayName("POST /decisions/{id}/review（§2.1 權限列 13：BUYER_LEAD、SYS_ADMIN）")
    class Review {

        @ParameterizedTest(name = "{0} 回 403")
        @ValueSource(strings = { "BUYER", "DATA_ADMIN", "VIEWER" })
        void nonLeadForbidden(String role) throws Exception {
            mockMvc.perform(post("/decisions/5/review").with(user(BUYER_EMAIL).roles(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

            verifyNoInteractions(commandService);
        }

        @Test
        @DisplayName("BUYER_LEAD 可覆核")
        void leadReviews() throws Exception {
            when(commandService.review(eq(5L), anyString(), any()))
                    .thenReturn(DecisionMapper.toResponse(
                            DecisionFixtures.decision(5L, DecisionType.ADOPT), DecisionFixtures.TODAY));

            mockMvc.perform(post("/decisions/5/review").with(user("lead@ssds.dev").roles("BUYER_LEAD")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(5));
        }
    }

    @Nested
    @DisplayName("POST /decisions/{id}/result（§2.1 權限列 12：含 DATA_ADMIN，不含 VIEWER）")
    class FillResult {

        private static final String VALID_BODY = """
                {"actualQty":120,"selloutStatus":"ON_TIME","realizedMarginRate":0.3500}
                """;

        @Test
        @DisplayName("VIEWER 回 403")
        void viewerForbidden() throws Exception {
            mockMvc.perform(postJson("/decisions/5/result", VALID_BODY).with(user(BUYER_EMAIL).roles("VIEWER")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

            verifyNoInteractions(commandService);
        }

        @Test
        @DisplayName("比率超出 0–1 回 400，fieldErrors 指向 realizedMarginRate")
        void rateOutOfRangeIsValidationError() throws Exception {
            String body = """
                    {"actualQty":120,"selloutStatus":"ON_TIME","realizedMarginRate":1.5}
                    """;

            mockMvc.perform(postJson("/decisions/5/result", body).with(user(BUYER_EMAIL).roles("DATA_ADMIN")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.fieldErrors[0].field").value("realizedMarginRate"));

            verifyNoInteractions(commandService);
        }

        @Test
        @DisplayName("DATA_ADMIN 可回填")
        void dataAdminFills() throws Exception {
            when(commandService.fillResult(anyLong(), any(), anyString(), any()))
                    .thenReturn(DecisionMapper.toResponse(
                            DecisionFixtures.decision(5L, DecisionType.ADOPT), DecisionFixtures.TODAY));

            mockMvc.perform(postJson("/decisions/5/result", VALID_BODY).with(user(BUYER_EMAIL).roles("DATA_ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(5));
        }
    }
}
