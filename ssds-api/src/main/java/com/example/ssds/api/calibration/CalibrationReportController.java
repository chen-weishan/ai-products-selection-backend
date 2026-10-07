package com.example.ssds.api.calibration;

import com.example.ssds.api.calibration.dto.BacktestRequest;
import com.example.ssds.api.calibration.dto.BacktestResponse;
import com.example.ssds.api.calibration.dto.CalibrationReportResponse;
import com.example.ssds.api.calibration.dto.GenerateCalibrationReportResponse;
import com.example.ssds.api.calibration.dto.ReviewCalibrationRequest;
import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.common.response.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 權重校準閉環（規格書 §FR-15、§8「校準」）。
 *
 * <p>AI 解讀（Agent 7）的觸發端點 {@code POST /calibration/reports/{id}/interpretation}
 * 由 {@code WeightCalibrationController} 提供，兩支 controller 路徑不重疊，
 * 因此類別層不設 {@code @RequestMapping}。
 */
@RestController
@RequiredArgsConstructor
public class CalibrationReportController {

    private final CalibrationReportService reportService;
    private final CalibrationReviewService reviewService;
    private final CalibrationBacktestService backtestService;
    private final CalibrationInterpretations interpretations;

    // §2.1 權限列 2：五個角色皆可讀
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/calibration/reports")
    public ApiResponse<PageResponse<CalibrationReportResponse>> list(@PageableDefault(size = 8) Pageable pageable) {
        return ApiResponse.success(reportService.list(pageable));
    }

    /** 尚未產生任何報告時 data 為 null。 */
    // §2.1 權限列 2：五個角色皆可讀
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/calibration/reports/latest")
    public ApiResponse<CalibrationReportResponse> latest() {
        return ApiResponse.success(reportService.latest());
    }

    // §2.1 權限列 17「核准權重校準建議」：僅 BUYER_LEAD
    @PreAuthorize("hasRole('BUYER_LEAD')")
    @PostMapping("/calibration/reports/{id}/approve")
    public ApiResponse<CalibrationReportResponse> review(
            @PathVariable Long id,
            @Valid @RequestBody ReviewCalibrationRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(reviewService.review(
                id, request, actorOf(authentication), httpRequest.getRemoteAddr()));
    }

    // §2.1 權限列 2：五個角色皆可讀（回測不寫入任何資料）
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/calibration/backtest")
    public ApiResponse<BacktestResponse> backtest(@Valid @RequestBody BacktestRequest request) {
        return ApiResponse.success(backtestService.compare(request));
    }

    /**
     * 立即產生（或重算待審核的）季度報告。規格 §8 未列此端點，屬設計決定：
     * 正式流程由 {@link CalibrationStatisticsJob} 每季執行，此端點供 demo 與補跑。
     *
     * <p>重算會清空 AI 解讀，因此和排程一樣自動建立 Agent 7 解讀任務（§FR-15 步驟 2）。
     * 建任務放在 controller：此時產生報告的交易已提交，建任務失敗不會連帶回滾報告。
     */
    @PreAuthorize("hasAnyRole('BUYER_LEAD', 'SYS_ADMIN')")
    @PostMapping("/calibration/reports")
    public ApiResponse<GenerateCalibrationReportResponse> generate(
            @RequestParam String quarter, Authentication authentication, HttpServletRequest httpRequest) {
        CalibrationReportResponse report =
                reportService.generate(quarter, actorOf(authentication), httpRequest.getRemoteAddr());
        Long taskId = interpretations.request(report.id(), report.quarter());
        return ApiResponse.success(new GenerateCalibrationReportResponse(report, taskId));
    }

    /** 匿名請求已先被 {@code @PreAuthorize} 擋下（403）；service 層的 CalibrationActors 再以資料庫角色複驗。 */
    private static String actorOf(Authentication authentication) {
        return authentication == null ? null : authentication.getName();
    }
}
