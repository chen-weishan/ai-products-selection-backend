package com.example.ssds.api.insight;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.insight.dto.ProductInsightResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/products/{productId}/product-insight")
@Tag(
        name = "Product Insight（賣點與風險）",
        description = "Agent 3 合併輸出；AI 任務 API 中沿用 SELLING_POINT 相容碼")
public class ProductInsightController {
    private final ProductInsightService service;

    public ProductInsightController(ProductInsightService service) {
        this.service = service;
    }

    @GetMapping("/latest")
    @Operation(summary = "查詢品項最新的賣點與風險洞察")
    public ApiResponse<ProductInsightResponse> latest(
            @PathVariable("productId") Long productId) {
        return ApiResponse.success(service.latest(productId));
    }
}
