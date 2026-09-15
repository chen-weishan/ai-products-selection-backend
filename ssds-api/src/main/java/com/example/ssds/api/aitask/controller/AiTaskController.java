package com.example.ssds.api.aitask.controller;

import com.example.ssds.api.aitask.dto.AiTaskStatusResponse;
import com.example.ssds.api.aitask.service.AiTaskQueryService;
import com.example.ssds.api.common.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR-07 任務端點的最小查詢切片，供 FR-03 評分完成後自動更新清單。 */
@RestController
@RequestMapping("/ai/tasks")
public class AiTaskController {

    private final AiTaskQueryService queryService;

    public AiTaskController(AiTaskQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<AiTaskStatusResponse> getById(@PathVariable(name = "id") Long id) {
        return ApiResponse.success(queryService.getStatus(id));
    }
}
