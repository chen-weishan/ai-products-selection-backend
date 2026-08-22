package com.example.ssds.api.review;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.review.dto.ReviewRiskResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/products/{productId}/review-risk")
public class ReviewRiskController {
    private final ReviewRiskService service;

    public ReviewRiskController(ReviewRiskService service) {
        this.service = service;
    }

    @GetMapping("/latest")
    public ApiResponse<ReviewRiskResponse> latest(@PathVariable Long productId) {
        return ApiResponse.success(service.latest(productId));
    }
}
