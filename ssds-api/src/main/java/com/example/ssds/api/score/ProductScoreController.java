package com.example.ssds.api.score;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.score.dto.ScoreDetailResponse;
import com.example.ssds.api.score.dto.ScoreHistoryPointResponse;
import com.example.ssds.api.score.dto.ScoreRecalculationResponse;
import com.example.ssds.core.domain.SceneType;

import lombok.RequiredArgsConstructor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
@Tag(name = "Product Scores", description = "單一品項的正式評分快照、歷史與人工重算")
@SecurityRequirement(name = "bearerAuth")
public class ProductScoreController {

    private final ScoreQueryService queryService;
    private final ScoreCommandService commandService;

    // §2.1 權限列 2「檢視排行、品項詳情、趨勢」：五個角色皆可讀，故只要求已登入
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}/scores")
    @Operation(summary = "查詢品項正式分數快照", description = "省略 scene 時回傳該 period 的主情境。")
    public ApiResponse<ScoreDetailResponse> snapshot(
            @PathVariable Long id,
            @RequestParam String period,
            @RequestParam(required = false) SceneType scene) {
        return ApiResponse.success(queryService.snapshot(id, period, scene));
    }

    // §2.1 權限列 2「檢視排行、品項詳情、趨勢」：五個角色皆可讀，故只要求已登入
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}/scores/history")
    @Operation(summary = "查詢品項歷史分數", description = "包含已被重算取代的 inactive 快照。")
    public ApiResponse<List<ScoreHistoryPointResponse>> history(
            @PathVariable Long id,
            @RequestParam(required = false) SceneType scene) {
        return ApiResponse.success(queryService.history(id, scene));
    }

    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    @PostMapping("/{id}/scores/recalculate")
    @Operation(summary = "純計算重算單一品項", description = "不呼叫 LLM，也不建立 AI task。")
    public ApiResponse<ScoreRecalculationResponse> recalculate(
            @PathVariable Long id,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(commandService.recalculate(
                id, authentication.getName(), httpRequest.getRemoteAddr()));
    }
}
