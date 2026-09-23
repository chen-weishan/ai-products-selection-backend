package com.example.ssds.api.score;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
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
import com.example.ssds.api.score.dto.RankingSummaryResponse;
import com.example.ssds.api.score.dto.ScoreRankingRowResponse;
import com.example.ssds.api.score.dto.SimulateRequest;
import com.example.ssds.core.domain.SceneType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

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
@Tag(name = "Scores", description = "正式評分快照、四榜排行、扣分明細與唯讀試算")
@SecurityRequirement(name = "bearerAuth")
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
    // §2.1 權限列 2「檢視排行、品項詳情、趨勢」：五個角色皆可讀，故只要求已登入
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/ranking")
    @Operation(summary = "查詢正式分數排行", description = "只回傳 active 且品項未軟刪除的快照。")
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

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/ranking/summary")
    @Operation(summary = "查詢排行全榜分級分布", description = "統計完整篩選結果，不受 page、size 影響。")
    public ApiResponse<RankingSummaryResponse> rankingSummary(
            @RequestParam String period,
            @RequestParam(required = false) SceneType scene,
            @RequestParam(required = false) Long categoryId) {
        return ApiResponse.success(queryService.rankingSummary(period, scene, categoryId));
    }

    // §2.1 權限列 2「檢視排行、品項詳情、趨勢」：五個角色皆可讀，故只要求已登入
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}/deductions")
    @Operation(summary = "查詢正式分數的三項扣分明細")
    public ApiResponse<ScoreDeductionsResponse> deductions(@PathVariable Long id) {
        return ApiResponse.success(queryService.deductions(id));
    }

    // §2.1 權限列 2「檢視排行、品項詳情、趨勢」：五個角色皆可讀，故只要求已登入
    @PreAuthorize("isAuthenticated()")
    @PostMapping ("/simulate")
    @Operation(summary = "依指定權重版本試算排行", description = "唯讀計算，不建立或修改正式快照。")
    public ApiResponse<List<ScoreRankingRowResponse>> simulate(
            @Valid @RequestBody SimulateRequest simulateRequest) {
        return ApiResponse.success(simulationService.simulate(simulateRequest));
    }
}
