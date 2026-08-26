package com.example.ssds.api.recommendation;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.recommendation.dto.RecommendationResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/products/{productId}/recommendation")
public class RecommendationController {
    private final RecommendationService service;

    public RecommendationController(RecommendationService service) {
        this.service = service;
    }

    @GetMapping("/latest")
    public ApiResponse<RecommendationResponse> latest(@PathVariable Long productId) {
        return ApiResponse.success(service.latest(productId));
    }
}
