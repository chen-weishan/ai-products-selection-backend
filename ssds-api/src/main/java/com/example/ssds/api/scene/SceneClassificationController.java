package com.example.ssds.api.scene;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.scene.dto.SceneClassificationResponse;
import com.example.ssds.api.scene.dto.SceneLogResponse;
import com.example.ssds.api.scene.dto.SceneOverrideRequest;
import com.example.ssds.api.scene.dto.SceneOverrideResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/products/{productId}")
@Tag(name = "Product Scenes", description = "AI 情境判定、人工覆寫與可稽核歷程")
@SecurityRequirement(name = "bearerAuth")
public class SceneClassificationController {
    private final SceneClassificationService service;
    private final SceneOverrideService overrideService;

    public SceneClassificationController(
            SceneClassificationService service,
            SceneOverrideService overrideService) {
        this.service = service;
        this.overrideService = overrideService;
    }

    /** S-06 情境判定橫幅讀取最新結果。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/scene-classification/latest")
    @Operation(summary = "查詢最新情境判定")
    public ApiResponse<SceneClassificationResponse> latest(
            @PathVariable("productId") Long productId) {
        return ApiResponse.success(service.latest(productId));
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/scene-log")
    @Operation(summary = "查詢情境判定與人工覆寫歷程")
    public ApiResponse<List<SceneLogResponse>> history(
            @PathVariable("productId") Long productId) {
        return ApiResponse.success(service.history(productId));
    }

    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'SYS_ADMIN')")
    @PutMapping("/scene-override")
    @Operation(
            summary = "人工覆寫評分情境",
            description = "理由必填；同一交易寫入覆寫紀錄、正式分數快照與 audit log。")
    public ApiResponse<SceneOverrideResponse> override(
            @PathVariable("productId") Long productId,
            @Valid @RequestBody SceneOverrideRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(overrideService.override(
                productId,
                request,
                authentication.getName(),
                httpRequest.getRemoteAddr()));
    }
}
