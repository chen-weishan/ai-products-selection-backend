package com.example.ssds.api.insight;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.insight.dto.ProductInsightResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/products/{productId}/product-insight")
public class ProductInsightController {
    private final ProductInsightService service;

    public ProductInsightController(ProductInsightService service) {
        this.service = service;
    }

    @GetMapping("/latest")
    public ApiResponse<ProductInsightResponse> latest(
            @PathVariable("productId") Long productId) {
        return ApiResponse.success(service.latest(productId));
    }
}
