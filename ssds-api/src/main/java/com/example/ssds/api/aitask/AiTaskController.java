package com.example.ssds.api.aitask;

import com.example.ssds.api.aitask.dto.*;
import com.example.ssds.api.common.response.AppResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ai/tasks")
public class AiTaskController {
    private final AiTaskService service;

    public AiTaskController(AiTaskService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AppResponse<AiTaskResponse>> create(
            @Valid @RequestBody CreateAiTaskRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AppResponse.success(service.create(request)));
    }

    @GetMapping("/{taskId}")
    public AppResponse<AiTaskResponse> get(@PathVariable Long taskId) {
        return AppResponse.success(service.get(taskId));
    }

    @GetMapping("/{taskId}/items")
    public AppResponse<List<AiTaskItemResponse>> items(@PathVariable Long taskId) {
        return AppResponse.success(service.items(taskId));
    }
}
