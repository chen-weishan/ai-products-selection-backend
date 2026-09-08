package com.example.ssds.api.aitask;

import com.example.ssds.api.aitask.dto.*;
import com.example.ssds.api.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai/tasks")
@Tag(name = "AI Tasks", description = "非同步 AI 任務；SELLING_POINT 是 Product Insight（賣點與風險）的相容碼")
public class AiTaskController {
    private final AiTaskService service;

    public AiTaskController(AiTaskService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(
            summary = "建立 AI 任務",
            description = "FULL_ANALYSIS 編排四個邏輯 Agent 階段；requestCount 記錄實際外部請求嘗試，會因快取、資料不足或重試而不等於 4。")
    public ResponseEntity<ApiResponse<AiTaskResponse>> create(
            @Valid @RequestBody CreateAiTaskRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(service.create(request)));
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "查詢 AI 任務進度與實際外部請求統計")
    public ApiResponse<AiTaskResponse> get(@PathVariable("taskId") Long taskId) {
        return ApiResponse.success(service.get(taskId));
    }

    @GetMapping("/{taskId}/items")
    @Operation(summary = "查詢 AI 任務品項結果")
    public ApiResponse<List<AiTaskItemResponse>> items(@PathVariable("taskId") Long taskId) {
        return ApiResponse.success(service.items(taskId));
    }
}
