package com.example.ssds.api.trend;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.trend.dto.TrendInterpretationResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/trends/keywords/{keywordId}/interpretation")
public class TrendInterpretationController {
    private final TrendInterpretationService service;

    public TrendInterpretationController(TrendInterpretationService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<TrendInterpretationResponse> interpret(
            @PathVariable Long keywordId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return ApiResponse.success(service.interpret(keywordId, forceRefresh));
    }

    @GetMapping("/latest")
    public ApiResponse<TrendInterpretationResponse> latest(@PathVariable Long keywordId) {
        return ApiResponse.success(service.latest(keywordId));
    }
}
