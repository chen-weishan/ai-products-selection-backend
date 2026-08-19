package com.example.ssds.api.scene;

import com.example.ssds.api.common.response.AppResponse;
import com.example.ssds.api.scene.dto.SceneClassificationResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products/{productId}/scene-classification")
public class SceneClassificationController {
    private final SceneClassificationService service;

    public SceneClassificationController(SceneClassificationService service) {
        this.service = service;
    }

    /** S-06 情境判定橫幅讀取最新結果。 */
    @GetMapping("/latest")
    public AppResponse<SceneClassificationResponse> latest(@PathVariable Long productId) {
        return AppResponse.success(service.latest(productId));
    }
}
