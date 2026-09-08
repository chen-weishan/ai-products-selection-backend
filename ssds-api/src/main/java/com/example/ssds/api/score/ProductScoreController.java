package com.example.ssds.api.score;

import java.util.List;

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

    // TODO 權限列 2（§2.1）：全部已登入角色可讀 → @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}/scores")
    public ApiResponse<ScoreDetailResponse> snapshot(
            @PathVariable Long id,
            @RequestParam String period,
            @RequestParam(required = false) SceneType scene) {
        return ApiResponse.success(queryService.snapshot(id, period, scene));
    }

    // TODO 權限列 2（§2.1）：全部已登入角色可讀 → @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}/scores/history")
    public ApiResponse<List<ScoreHistoryPointResponse>> history(
            @PathVariable Long id,
            @RequestParam(required = false) SceneType scene) {
        return ApiResponse.success(queryService.history(id, scene));
    }
}
