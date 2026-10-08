package com.example.ssds.api.aibudget;

import com.example.ssds.ai.budget.DailyAiBudget;
import com.example.ssds.api.common.response.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.servlet.http.HttpServletRequest;
import com.example.ssds.api.admin.RuntimeSettingsService;

/** v3.0 GET /ai/budgets：三池即時請求數、快取命中與重置時間。 */
@RestController
@RequestMapping(value = "/ai/budgets", produces = MediaType.APPLICATION_JSON_VALUE)
public class AiBudgetController {
    private final DailyAiBudget budget;
    private final RuntimeSettingsService settings;

    public AiBudgetController(DailyAiBudget budget, RuntimeSettingsService settings) {
        this.budget = budget;
        this.settings = settings;
    }

    @GetMapping
    public ApiResponse<DailyAiBudget.Snapshot> current() {
        return ApiResponse.success(budget.snapshot());
    }

    @PutMapping
    @PreAuthorize("hasRole('SYS_ADMIN')")
    public ApiResponse<RuntimeSettingsService.AiConfig> update(
            @RequestBody RuntimeSettingsService.BudgetUpdate request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(settings.updateBudget(request, servletRequest.getRemoteAddr()));
    }
}
