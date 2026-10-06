package com.example.ssds.api.sourcing;

import com.example.ssds.api.aitask.dto.AiTaskResponse;
import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.sourcing.dto.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sourcing")
@Validated
public class SourcingScoutController {
    private final SourcingScoutService service;
    private final SourcingPriorityCommandService priorityCommands;
    private final SourcingQueueService queueService;
    public SourcingScoutController(
            SourcingScoutService service,
            SourcingPriorityCommandService priorityCommands,
            SourcingQueueService queueService) {
        this.service = service;
        this.priorityCommands = priorityCommands;
        this.queueService = queueService;
    }

    @GetMapping("/queue")
    public ApiResponse<SourcingQueueResponse> queue(
            @RequestParam(defaultValue = "ALL") SourcingQueueFilter status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return ApiResponse.success(queueService.getQueue(status, page, size));
    }
    @PostMapping("/scout")
    public ResponseEntity<ApiResponse<AiTaskResponse>> scout(@Valid @RequestBody SourcingScoutRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(service.start(request)));
    }
    @GetMapping("/candidates/{productId}/report")
    public ApiResponse<SourcingScoutResponse> latest(@PathVariable("productId") Long productId) {
        return ApiResponse.success(service.latest(productId));
    }

    @GetMapping("/scout-results/{itemId}")
    public ApiResponse<SourcingScoutResponse> latestResult(@PathVariable("itemId") Long itemId) {
        return ApiResponse.success(service.latestResult(itemId));
    }

    @PostMapping("/scout-results/{itemId}/watch")
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<SourcingScoutResponse> watchResult(
            @PathVariable("itemId") Long itemId,
            Authentication authentication) {
        return ApiResponse.success(service.watchResult(itemId, authentication.getName()));
    }

    @PostMapping("/scout-results/{itemId}/prioritize")
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<SourcingScoutResponse> prioritizeResult(@PathVariable("itemId") Long itemId) {
        return ApiResponse.success(service.prioritizeResult(itemId));
    }

    @PostMapping("/candidates/{productId}/watch")
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<SourcingPriorityActionResponse> watch(
            @PathVariable("productId") Long productId,
            Authentication authentication) {
        return ApiResponse.success(priorityCommands.watch(productId, authentication.getName()));
    }

    @PostMapping("/candidates/{productId}/prioritize")
    @PreAuthorize("hasAnyRole('BUYER', 'BUYER_LEAD', 'DATA_ADMIN', 'SYS_ADMIN')")
    public ApiResponse<SourcingPriorityActionResponse> prioritize(
            @PathVariable("productId") Long productId) {
        return ApiResponse.success(priorityCommands.prioritize(productId));
    }
}
