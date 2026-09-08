package com.example.ssds.api.score;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.common.response.PageResponse;
import com.example.ssds.api.score.dto.ScoreDeductionsResponse;
import com.example.ssds.api.score.dto.ScoreRankingRowResponse;
import com.example.ssds.api.score.dto.SimulateRequest;
import com.example.ssds.core.domain.SceneType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * 選品分數（規格書 §FR-04、§8.2）。
 *
 * <p>
 * 路徑不含 {@code /api/v1} —— 已由 {@code server.servlet.context-path} 統一設定
 * （CONTEXT.md §5）。
 */
@RestController
@Validated
@RequestMapping("/scores")
@RequiredArgsConstructor
public class ScoreController {

    private final ScoreQueryService queryService;
    private final ScoreSimulationService simulationService;

    /**
     * 排行清單（依榜查詢）。查無資料回空頁，不是 404。
     *
     * <p>
     * 不用 {@code @PageableDefault}：排序規則固定寫在 JPQL 裡，
     * 若讓 Spring 從 query string 組出帶 Sort 的 Pageable，
     * 會和 JPQL 的 order by 打架。
     */
    // TODO 權限列 2（§2.1）：全部已登入角色可讀 → @PreAuthorize("isAuthenticated()")
    @GetMapping("/ranking")
    public ApiResponse<PageResponse<ScoreRankingRowResponse>> ranking(
            @RequestParam String period,
            @RequestParam(required = false) SceneType scene,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "頁碼不得為負數") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每頁筆數至少為 1") int size) {

        Page<ScoreRankingRowResponse> result = queryService.ranking(period, scene, categoryId,
                PageRequest.of(page, size));
        return ApiResponse.success(PageResponse.from(result));
    }

    // TODO 權限列 2（§2.1）：全部已登入角色可讀 → @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}/deductions")
    public ApiResponse<ScoreDeductionsResponse> deductions(@PathVariable Long id) {
        return ApiResponse.success(queryService.deductions(id));
    }

    // TODO 權限列 2（§2.1）：全部已登入角色可讀 → @PreAuthorize("isAuthenticated()")
    @PostMapping ("/simulate")
    public ApiResponse<List<ScoreRankingRowResponse>> simulate(
            @Valid @RequestBody SimulateRequest simulateRequest) {
        return ApiResponse.success(simulationService.simulate(simulateRequest));
    }
}
