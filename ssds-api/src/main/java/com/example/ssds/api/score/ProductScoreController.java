package com.example.ssds.api.score;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.score.dto.ScoreDetailResponse;
import com.example.ssds.api.score.dto.ScoreHistoryPointResponse;
import com.example.ssds.core.domain.SceneType;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductScoreController {

    private final ScoreQueryService queryService;

    // §2.1 權限列 2「檢視排行、品項詳情、趨勢」：五個角色皆可讀，故只要求已登入
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}/scores")
    public ApiResponse<ScoreDetailResponse> snapshot(
            @PathVariable Long id,
            @RequestParam String period,
            @RequestParam(required = false) SceneType scene) {
        return ApiResponse.success(queryService.snapshot(id, period, scene));
    }

    // §2.1 權限列 2「檢視排行、品項詳情、趨勢」：五個角色皆可讀，故只要求已登入
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}/scores/history")
    public ApiResponse<List<ScoreHistoryPointResponse>> history(
            @PathVariable Long id,
            @RequestParam(required = false) SceneType scene) {
        return ApiResponse.success(queryService.history(id, scene));
    }
}
