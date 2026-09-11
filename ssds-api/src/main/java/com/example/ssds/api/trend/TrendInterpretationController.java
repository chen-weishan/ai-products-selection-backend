package com.example.ssds.api.trend;

import com.example.ssds.api.aitask.AiTaskService;
import com.example.ssds.api.aitask.dto.*;
import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.trend.dto.TrendInterpretationResponse;
import com.example.ssds.core.domain.AiTaskType;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/trends/keywords/{keywordId}/interpretation")
public class TrendInterpretationController {
    private final TrendInterpretationService service;
    private final AiTaskService taskService;

    public TrendInterpretationController(TrendInterpretationService service, AiTaskService taskService) {
        this.service = service;
        this.taskService = taskService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AiTaskResponse>> interpret(
            @PathVariable("keywordId") Long keywordId,
            @RequestParam(name = "forceRefresh", defaultValue = "false") boolean forceRefresh) {
        AiTaskResponse task = taskService.create(new CreateAiTaskRequest(
                AiTaskType.TREND_INTERPRET,
                List.of(),
                List.of(keywordId),
                new CreateAiTaskRequest.Options(forceRefresh)));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(task));
    }

    @GetMapping("/latest")
    public ApiResponse<TrendInterpretationResponse> latest(
            @PathVariable("keywordId") Long keywordId) {
        return ApiResponse.success(service.latest(keywordId));
    }
}
