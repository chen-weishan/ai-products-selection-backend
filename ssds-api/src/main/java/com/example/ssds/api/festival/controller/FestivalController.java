package com.example.ssds.api.festival.controller;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.festival.dto.FestivalCreateRequest;
import com.example.ssds.api.festival.dto.FestivalResponse;
import com.example.ssds.api.festival.dto.FestivalUpdateRequest;
import com.example.ssds.api.festival.service.FestivalCommandService;
import com.example.ssds.api.festival.service.FestivalQueryService;
import com.example.ssds.api.product.dto.FestivalOptionResponse;

import jakarta.validation.Valid;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 節慶檔期（FR-17-1、畫面 S-20、§9 API 清單）。
 *
 * <p>{@code GET /festivals} 一支雙用：帶 {@code year} 回年度檔期全欄位（S-20 標記 1、2），
 * 不帶則回品項表單的下拉選項——後者原本在 {@code ProductReferenceController}，
 * 為了讓規格書明文的 {@code /festivals?year=} 成立而收攏到這裡，回應格式完全不變。
 */
@RestController
@RequestMapping(value = "/festivals", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class FestivalController {

    /**
     * 規格書 §3.2：系統時區統一 Asia/Taipei。
     *
     * <p>不用無參數的 {@code LocalDate.now()}——那取的是 JVM 預設時區，部署到 UTC 環境時
     * 每天有八小時會把「今天」算錯一天，AC-17-2 的窗狀態就跟著錯。
     * 與 {@code ProductFallbackScoringService} 等既有服務的寫法一致。
     */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");

    private final FestivalQueryService festivalQueryService;
    private final FestivalCommandService festivalCommandService;

    /**
     * 年度檔期（S-20 標記 1、2）。
     *
     * <p>與下面那支同路徑，靠 {@code params} 條件區分，兩者互斥所以不會是模糊對應。
     * 不寫成一支收選用參數再回 {@code ApiResponse<?>}：wildcard 會讓 springdoc
     * 推不出回應型別而把 schema 整個略過，§3.3 要求後端是 OpenAPI 契約的單一事實來源。
     */
    @GetMapping(params = "year")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<FestivalResponse>> getFestivalsByYear(
            @RequestParam(name = "year") int year) {
        return ApiResponse.success(
                festivalQueryService.getFestivalsByYear(year, LocalDate.now(BUSINESS_ZONE)));
    }

    /**
     * 品項表單的節慶下拉選項：同一節慶跨年度只回一筆。
     *
     * <p>原本在 {@code ProductReferenceController}，為了讓規格書 §9 明文的
     * {@code /festivals?year=} 成立而收攏到這裡。<b>回應格式與原本完全相同。</b>
     */
    @GetMapping(params = "!year")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<FestivalOptionResponse>> getFestivalOptions() {
        return ApiResponse.success(festivalQueryService.getFestivalOptions());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('BUYER_LEAD','DATA_ADMIN','SYS_ADMIN')")
    public ApiResponse<FestivalResponse> create(
            @Valid @RequestBody FestivalCreateRequest request) {
        return ApiResponse.success(festivalCommandService.create(request, LocalDate.now(BUSINESS_ZONE)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('BUYER_LEAD','DATA_ADMIN','SYS_ADMIN')")
    public ApiResponse<FestivalResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody FestivalUpdateRequest request) {
        return ApiResponse.success(festivalCommandService.update(id, request, LocalDate.now(BUSINESS_ZONE)));
    }

    /** 回 204：沒有 body，因此不包 §8.1 的成功封套——包了等於回 200 帶 body。 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('BUYER_LEAD','DATA_ADMIN','SYS_ADMIN')")
    public void delete(@PathVariable Long id) {
        festivalCommandService.delete(id);
    }
}
