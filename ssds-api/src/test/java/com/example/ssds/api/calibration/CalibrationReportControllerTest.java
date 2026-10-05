package com.example.ssds.api.calibration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ssds.api.aitask.service.AiTaskService;
import com.example.ssds.api.calibration.dto.CalibrationReportResponse;
import com.example.ssds.api.calibration.dto.ReviewCalibrationRequest;
import com.example.ssds.api.security.SecurityConfig;
import com.example.ssds.core.domain.CalibrationStatus;
import com.example.ssds.util.JwtUtils;
import com.example.ssds.util.UserDetailsServiceImpl;
import java.util.List;
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
 * FR-15 Controller 層：§2.1 角色權限（{@code @PreAuthorize}）與 DTO 驗證錯誤的 HTTP 轉換。
 * 業務規則與 service 層的資料庫角色複驗由 {@link CalibrationReviewServiceTest}、{@link CalibrationReportServiceTest} 涵蓋。
 *
 * <p>匯入 {@link SecurityConfig} 是為了 {@code @EnableMethodSecurity}：
 * {@code @WebMvcTest} 不掃 {@code @Configuration}，少了它 {@code @PreAuthorize} 不生效，403 測試會假通過。
 */
@WebMvcTest({ CalibrationReportController.class, WeightCalibrationController.class })
@Import(SecurityConfig.class)
class CalibrationReportControllerTest {

    private static final String EMAIL = "lead@ssds.dev";
    private static final String APPROVE_BODY = "{\"action\":\"APPROVE\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CalibrationReportService reportService;
    @MockitoBean
    private CalibrationReviewService reviewService;
    @MockitoBean
    private CalibrationBacktestService backtestService;
    @MockitoBean
    private AiTaskService aiTaskService;
    // JwtAuthFilter 是 @Component Filter，@WebMvcTest 會載入它，其相依須補上
    @MockitoBean
    private JwtUtils jwtUtils;
    @MockitoBean
    private UserDetailsServiceImpl userDetailsService;

    private static MockHttpServletRequestBuilder postJson(String url, String body) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static CalibrationReportResponse report(long id, CalibrationStatus status) {
        return new CalibrationReportResponse(id, "2026Q3", 8, 200, true, "樣本數不足", status,
                null, null, null, List.of(), List.of(), null, null, List.of(), null, null, null, null, null);
    }

    @Nested
    @DisplayName("POST /calibration/reports/{id}/approve（§2.1 權限列 17：僅 BUYER_LEAD）")
    class Review {

        @Test
        @DisplayName("BUYER_LEAD 可審核")
        void leadReviews() throws Exception {
            when(reviewService.review(eq(3L), any(ReviewCalibrationRequest.class), anyString(), any()))
                    .thenReturn(report(3L, CalibrationStatus.APPROVED));

            mockMvc.perform(postJson("/calibration/reports/3/approve", APPROVE_BODY)
                            .with(user(EMAIL).roles("BUYER_LEAD")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(3))
                    .andExpect(jsonPath("$.data.status").value("APPROVED"));

            verify(reviewService).review(eq(3L), any(ReviewCalibrationRequest.class), eq(EMAIL), any());
        }

        @ParameterizedTest(name = "{0} 回 403 FORBIDDEN，不進 service")
        @ValueSource(strings = { "BUYER", "DATA_ADMIN", "SYS_ADMIN", "VIEWER" })
        void otherRolesForbidden(String role) throws Exception {
            mockMvc.perform(postJson("/calibration/reports/3/approve", APPROVE_BODY)
                            .with(user(EMAIL).roles(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

            verifyNoInteractions(reviewService);
        }

        @Test
        @DisplayName("未登入回 403，不進 service")
        void anonymousForbidden() throws Exception {
            mockMvc.perform(postJson("/calibration/reports/3/approve", APPROVE_BODY))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(reviewService);
        }

        @Test
        @DisplayName("缺 action 回 400 VALIDATION_FAILED，fieldErrors 指向 action")
        void missingActionIsValidationError() throws Exception {
            mockMvc.perform(postJson("/calibration/reports/3/approve", "{\"comment\":\"ok\"}")
                            .with(user(EMAIL).roles("BUYER_LEAD")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.fieldErrors[0].field").value("action"));

            verifyNoInteractions(reviewService);
        }

        @Test
        @DisplayName("action 或因子代碼不在列舉內回 400，不是 500")
        void unknownEnumIsBadRequest() throws Exception {
            mockMvc.perform(postJson("/calibration/reports/3/approve", "{\"action\":\"MAYBE\"}")
                            .with(user(EMAIL).roles("BUYER_LEAD")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
            mockMvc.perform(postJson("/calibration/reports/3/approve",
                            "{\"action\":\"PARTIAL\",\"acceptedFactors\":[\"NOPE\"]}")
                            .with(user(EMAIL).roles("BUYER_LEAD")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

            verifyNoInteractions(reviewService);
        }

        @Test
        @DisplayName("comment 超過 200 字回 400")
        void commentTooLong() throws Exception {
            String body = "{\"action\":\"REJECT\",\"comment\":\"" + "x".repeat(201) + "\"}";

            mockMvc.perform(postJson("/calibration/reports/3/approve", body)
                            .with(user(EMAIL).roles("BUYER_LEAD")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.fieldErrors[0].field").value("comment"));

            verifyNoInteractions(reviewService);
        }
    }

    @Nested
    @DisplayName("POST /calibration/reports?quarter=（設計決定：BUYER_LEAD、SYS_ADMIN）")
    class Generate {

        @ParameterizedTest(name = "{0} 可產生")
        @ValueSource(strings = { "BUYER_LEAD", "SYS_ADMIN" })
        void allowedRolesGenerate(String role) throws Exception {
            when(reportService.generate(eq("2026Q3"), anyString(), any()))
                    .thenReturn(report(5L, CalibrationStatus.PENDING));

            mockMvc.perform(post("/calibration/reports").param("quarter", "2026Q3")
                            .with(user(EMAIL).roles(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(5));
        }

        @ParameterizedTest(name = "{0} 回 403")
        @ValueSource(strings = { "BUYER", "DATA_ADMIN", "VIEWER" })
        void otherRolesForbidden(String role) throws Exception {
            mockMvc.perform(post("/calibration/reports").param("quarter", "2026Q3")
                            .with(user(EMAIL).roles(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

            verifyNoInteractions(reportService);
        }

        @Test
        @DisplayName("缺 quarter 回 400，不是 500")
        void missingQuarterIsBadRequest() throws Exception {
            mockMvc.perform(post("/calibration/reports").with(user(EMAIL).roles("BUYER_LEAD")))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(reportService);
        }
    }

    @Nested
    @DisplayName("POST /calibration/reports/{id}/interpretation（Agent 7，設計決定：BUYER_LEAD、SYS_ADMIN）")
    class Interpretation {

        @ParameterizedTest(name = "{0} 可觸發解讀，回 202")
        @ValueSource(strings = { "BUYER_LEAD", "SYS_ADMIN" })
        void allowedRolesTrigger(String role) throws Exception {
            when(aiTaskService.create(any())).thenReturn(null);

            mockMvc.perform(postJson("/calibration/reports/3/interpretation", "{\"forceRefresh\":false}")
                            .with(user(EMAIL).roles(role)))
                    .andExpect(status().isAccepted());
        }

        @ParameterizedTest(name = "{0} 回 403，不建 AI 任務")
        @ValueSource(strings = { "BUYER", "DATA_ADMIN", "VIEWER" })
        void otherRolesForbidden(String role) throws Exception {
            mockMvc.perform(postJson("/calibration/reports/3/interpretation", "{\"forceRefresh\":false}")
                            .with(user(EMAIL).roles(role)))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(aiTaskService);
        }

        @Test
        @DisplayName("未登入回 403，不建 AI 任務（HTTP 層 permitAll，只靠 @PreAuthorize 擋）")
        void anonymousForbidden() throws Exception {
            mockMvc.perform(postJson("/calibration/reports/3/interpretation", "{\"forceRefresh\":false}"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(aiTaskService);
        }
    }

    @Nested
    @DisplayName("讀取與回測（§2.1 權限列 2：五個角色皆可）")
    class ReadOnly {

        @Test
        @DisplayName("VIEWER 可讀最新報告")
        void viewerReadsLatest() throws Exception {
            when(reportService.latest()).thenReturn(report(7L, CalibrationStatus.PENDING));

            mockMvc.perform(get("/calibration/reports/latest").with(user(EMAIL).roles("VIEWER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.quarter").value("2026Q3"));
        }

        @Test
        @DisplayName("未登入讀取回 403")
        void anonymousForbidden() throws Exception {
            mockMvc.perform(get("/calibration/reports/latest"))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(reportService);
        }

        @Test
        @DisplayName("回測缺版本 B 回 400，fieldErrors 指向 versionBId")
        void backtestMissingVersionB() throws Exception {
            mockMvc.perform(postJson("/calibration/backtest", "{\"versionAId\":1}")
                            .with(user(EMAIL).roles("VIEWER")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.fieldErrors[0].field").value("versionBId"));

            verifyNoInteractions(backtestService);
        }
    }
}
