package com.example.ssds.api.review;

import com.example.ssds.api.common.response.AppResponse;
import com.example.ssds.api.review.dto.ReviewRiskResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products/{productId}/review-risk")
public class ReviewRiskController {
    private final ReviewRiskService service;

    public ReviewRiskController(ReviewRiskService service) {
        this.service = service;
    }

    @GetMapping("/latest")
    public AppResponse<ReviewRiskResponse> latest(@PathVariable Long productId) {
        return AppResponse.success(service.latest(productId));
    }
}
