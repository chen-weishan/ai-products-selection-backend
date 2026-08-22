package com.example.ssds.api.scene;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.scene.dto.SceneClassificationResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/products/{productId}/scene-classification")
public class SceneClassificationController {
    private final SceneClassificationService service;

    public SceneClassificationController(SceneClassificationService service) {
        this.service = service;
    }

    /** S-06 情境判定橫幅讀取最新結果。 */
    @GetMapping("/latest")
    public ApiResponse<SceneClassificationResponse> latest(@PathVariable Long productId) {
        return ApiResponse.success(service.latest(productId));
    }
}
