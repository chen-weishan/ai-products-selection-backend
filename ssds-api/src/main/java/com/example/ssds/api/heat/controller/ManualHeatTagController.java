package com.example.ssds.api.heat.controller;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.heat.ManualHeatTagCommandService;
import com.example.ssds.api.heat.ManualHeatTagQueryService;
import com.example.ssds.api.heat.dto.ManualHeatTagCreateRequest;
import com.example.ssds.api.heat.dto.ManualHeatTagResponse;
import com.example.ssds.api.heat.dto.ManualHeatTagUpdateRequest;
import com.example.ssds.api.heat.dto.ResolvePlatformRequest;
import com.example.ssds.api.heat.dto.ResolvePlatformResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * S-15 人工熱度標記（規格書 FR-14-1）。
 *
 * <p>「30 秒內填完」是設計目標而非硬性 API 限制，因此本 controller 不對填寫時長
 * 做任何限制或計時——那是前端 UX 的責任（常駐「＋ 我看到一個」入口）。
 *
 * <p>權限依 §2.1 權限矩陣第 7 列「建立人工熱度標記」：VIEWER 無此權限，
 * 僅 BUYER／BUYER_LEAD／DATA_ADMIN／SYS_ADMIN 可新增／編輯／刪除／判定平台別。
 */
@RestController
@RequestMapping("/heat-tags")
@RequiredArgsConstructor
public class ManualHeatTagController {

    private static final String CAN_WRITE =
            "hasAnyRole('BUYER','BUYER_LEAD','DATA_ADMIN','SYS_ADMIN')";

    private final ManualHeatTagQueryService queryService;
    private final ManualHeatTagCommandService commandService;

    /** 五個角色皆可查詢（採購人員在瀏覽平台時就地標記，不限特定角色）。 */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}")
    public ApiResponse<ManualHeatTagResponse> get(@PathVariable Long id) {
        return ApiResponse.success(queryService.getById(id));
    }

    /**
     * S-15 標記清單（§8 API 表 {@code GET /heat-tags?scope=&days=}）。
     *
     * <p>{@code productId}／{@code keywordId} 是既有呼叫端（品項詳情頁、關鍵字詳情頁）
     * 用來看特定標的標記的既有用法，繼續支援；規格書 §8 要的 {@code scope}／{@code days}
     * 一般瀏覽（不綁定特定標的）由同一個 endpoint 一併提供，兩者不會同時使用。
     *
     * @param scope {@code MINE} 僅本人建立／{@code ALL} 全部（預設），忽略大小寫
     * @param days  觀察窗天數，預設 30
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ApiResponse<List<ManualHeatTagResponse>> list(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long keywordId,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) Integer days) {
        return ApiResponse.success(queryService.list(productId, keywordId, scope, days));
    }

    /**
     * AC-14-1：貼上連結後，送出前先看一下系統判定的平台別。
     *
     * <p>這是「建立標記」流程的一部分（送出前的預覽步驟），比照 §2.1 第 7 列權限，
     * VIEWER 不開放——否則等於變相讓 VIEWER 繞道試探建立流程。
     */
    @PreAuthorize(CAN_WRITE)
    @PostMapping("/resolve-platform")
    public ApiResponse<ResolvePlatformResponse> resolvePlatform(
            @Valid @RequestBody ResolvePlatformRequest request) {
        return ApiResponse.success(queryService.resolvePlatform(request.sourceUrl()));
    }

    @PreAuthorize(CAN_WRITE)
    @PostMapping
    public ResponseEntity<ApiResponse<ManualHeatTagResponse>> create(
            @Valid @RequestBody ManualHeatTagCreateRequest request) {
        ManualHeatTagResponse dto = commandService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/heat-tags/" + dto.id()))
                .body(ApiResponse.success(dto));
    }

    @PreAuthorize(CAN_WRITE)
    @PutMapping("/{id}")
    public ApiResponse<ManualHeatTagResponse> update(
            @PathVariable Long id, @Valid @RequestBody ManualHeatTagUpdateRequest request) {
        return ApiResponse.success(commandService.update(id, request));
    }

    @PreAuthorize(CAN_WRITE)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        commandService.delete(id);
        return ResponseEntity.noContent().build();
    }
}