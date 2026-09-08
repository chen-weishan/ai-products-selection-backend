package com.example.ssds.api.aitask;

import com.example.ssds.ai.client.DailyAiBudget;
import com.example.ssds.api.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** v3.0 GET /ai/budgets：三池即時請求數、快取命中與重置時間。 */
@RestController
@RequestMapping("/ai/budgets")
public class AiBudgetController {
    private final DailyAiBudget budget;

    public AiBudgetController(DailyAiBudget budget) {
        this.budget = budget;
    }

    @GetMapping
    public ApiResponse<DailyAiBudget.Snapshot> current() {
        return ApiResponse.success(budget.snapshot());
    }
}
