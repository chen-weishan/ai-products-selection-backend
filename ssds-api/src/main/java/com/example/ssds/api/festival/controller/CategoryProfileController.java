package com.example.ssds.api.festival.controller;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.festival.dto.CategoryClimateProfileResponse;
import com.example.ssds.api.festival.dto.CategoryClimateProfileUpdateRequest;
import com.example.ssds.api.festival.dto.CategoryLeadTimeResponse;
import com.example.ssds.api.festival.dto.CategoryLeadTimeUpdateRequest;
import com.example.ssds.api.festival.dto.CategoryProfileResponse;
import com.example.ssds.api.festival.service.CategoryProfileService;

import jakarta.validation.Valid;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 品類的兩張設定表（S-20 標記 3、5、權限矩陣第 20 列）。
 *
 * <p>與 {@code ProductReferenceController} 的 {@code GET /categories} 同路徑不同方法，
 * Spring 不會衝突；但兩支類別管同一個資源，改動時兩邊都要看。
 */
@RestController
@RequestMapping(value = "/categories", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class CategoryProfileController {

    private final CategoryProfileService categoryProfileService;

    /**
     * S-20 標記 3、5 的現值。規格書 §9 沒有這支，是為了讓維護頁看得到目前設定而新增的設計決定。
     */
    @GetMapping("/profiles")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<CategoryProfileResponse>> listProfiles() {
        return ApiResponse.success(categoryProfileService.listProfiles());
    }

    /** AC-17-3：這份前置天數同時供 FR-16 時效落差使用，只有這一份。 */
    @PutMapping("/{categoryId}/lead-time")
    @PreAuthorize("hasAnyRole('BUYER_LEAD','DATA_ADMIN','SYS_ADMIN')")
    public ApiResponse<CategoryLeadTimeResponse> updateLeadTime(
            @PathVariable Long categoryId,
            @Valid @RequestBody CategoryLeadTimeUpdateRequest request) {
        return ApiResponse.success(categoryProfileService.updateLeadTime(categoryId, request));
    }

    /** AC-17-5：品項未填適溫區間時沿用這裡的品類預設。 */
    @PutMapping("/{categoryId}/climate-profile")
    @PreAuthorize("hasAnyRole('BUYER_LEAD','DATA_ADMIN','SYS_ADMIN')")
    public ApiResponse<CategoryClimateProfileResponse> updateClimateProfile(
            @PathVariable Long categoryId,
            @Valid @RequestBody CategoryClimateProfileUpdateRequest request) {
        return ApiResponse.success(
                categoryProfileService.updateClimateProfile(categoryId, request));
    }
}
