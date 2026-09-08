package com.example.ssds.api.aitask;

import com.example.ssds.api.aitask.dto.*;
import com.example.ssds.api.common.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai/tasks")
public class AiTaskController {
    private final AiTaskService service;

    public AiTaskController(AiTaskService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AiTaskResponse>> create(
            @Valid @RequestBody CreateAiTaskRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(service.create(request)));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<AiTaskResponse> get(@PathVariable("taskId") Long taskId) {
        return ApiResponse.success(service.get(taskId));
    }

    @GetMapping("/{taskId}/items")
    public ApiResponse<List<AiTaskItemResponse>> items(@PathVariable("taskId") Long taskId) {
        return ApiResponse.success(service.items(taskId));
    }
}
