package com.example.ssds.api.sourcing;

import com.example.ssds.api.aitask.dto.AiTaskResponse;
import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.sourcing.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sourcing")
public class SourcingScoutController {
    private final SourcingScoutService service;
    public SourcingScoutController(SourcingScoutService service) { this.service = service; }
    @PostMapping("/scout")
    public ResponseEntity<ApiResponse<AiTaskResponse>> scout(@Valid @RequestBody SourcingScoutRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(service.start(request)));
    }
    @GetMapping("/candidates/{productId}/report")
    public ApiResponse<SourcingScoutResponse> latest(@PathVariable Long productId) {
        return ApiResponse.success(service.latest(productId));
    }
}
