package com.example.ssds.api.heat.controller;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.heat.HeatSourceCommandService;
import com.example.ssds.api.heat.HeatSourceQueryService;
import com.example.ssds.api.heat.dto.ExcludedHeatSourceResponse;
import com.example.ssds.api.heat.dto.HeatSourceDetailResponse;
import com.example.ssds.api.heat.dto.HeatSourceTestResponse;
import com.example.ssds.api.heat.dto.HeatSourceUpdateRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** S-16 熱度來源狀態（規格書 FR-14-2）。 */
@RestController
@RequestMapping("/heat-sources")
@RequiredArgsConstructor
public class HeatSourceController {

    private final HeatSourceQueryService queryService;
    private final HeatSourceCommandService commandService;

    /** 五個角色皆可檢視（畫面「即時查詢 availability，不快取」，§FR-02／§FR-14-2）。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ApiResponse<List<HeatSourceDetailResponse>> list() {
        return ApiResponse.success(queryService.list());
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}")
    public ApiResponse<HeatSourceDetailResponse> get(@PathVariable Long id) {
        return ApiResponse.success(queryService.getById(id));
    }

    /** 畫面「不採用來源」區塊：FB／TikTok／小紅書及其排除理由。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/excluded")
    public ApiResponse<List<ExcludedHeatSourceResponse>> excluded() {
        return ApiResponse.success(queryService.excludedSources());
    }

    /** AC-14-5：僅 SYS_ADMIN 可調整合成權重／啟用停用。 */
    @PreAuthorize("hasRole('SYS_ADMIN')")
    @PutMapping("/{id}")
    public ApiResponse<HeatSourceDetailResponse> update(
            @PathVariable Long id, @Valid @RequestBody HeatSourceUpdateRequest request) {
        return ApiResponse.success(commandService.update(id, request));
    }

    /** 測試連線，比照調整權重同樣限管理端操作。 */
    @PreAuthorize("hasRole('SYS_ADMIN')")
    @PostMapping("/{id}/test")
    public ApiResponse<HeatSourceTestResponse> test(@PathVariable Long id) {
        return ApiResponse.success(commandService.testConnection(id));
    }
}
