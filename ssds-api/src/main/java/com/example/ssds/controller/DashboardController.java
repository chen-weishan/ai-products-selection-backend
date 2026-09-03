package com.example.ssds.controller;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.dto.DashboardHeatSourcesResponseDto;
import com.example.ssds.api.dto.DashboardKpiResponseDto;
import com.example.ssds.api.dto.DashboardRankingsResponseDto;
import com.example.ssds.api.dto.DashboardSourcingSummaryResponseDto;
import com.example.ssds.api.dto.DashboardTodosResponseDto;
import com.example.ssds.api.service.DashboardService;
import com.example.ssds.core.domain.SceneType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

@Validated
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

        /**
         * §7.2.6／FR-02：週期一律以 Asia/Taipei 判定，與 product_score.period 的寫入規則一致。
         * 用 JVM 預設時區會在非台北時區的機器上算出不同週次。
         */
        private static final ZoneId TAIPEI_ZONE = ZoneId.of("Asia/Taipei");

        private final DashboardService dashboardService;

        /** §8.2 GET /dashboard/summary - KPI 四項 */
        @GetMapping("/summary")
        public ApiResponse<DashboardKpiResponseDto> getSummary(
                        @RequestParam(required = false) @Pattern(regexp = "\\d{4}W\\d{2}", message = "period 格式須為 2026W30") String period,
                        @RequestParam(required = false, defaultValue = "A") String track) {
                if (period == null) {
                        period = getCurrentWeek();
                }
                return ApiResponse.success(dashboardService.getKpi(period, track));
        }

        /** §8.2 GET /dashboard/rankings - 四榜排行（scene 省略時回傳四榜各 limit 筆） */
        @GetMapping("/rankings")
        public ApiResponse<DashboardRankingsResponseDto> getRankings(
                        @RequestParam(required = false) @Pattern(regexp = "\\d{4}W\\d{2}", message = "period 格式須為 2026W30") String period,
                        @RequestParam(required = false, defaultValue = "A") String track,
                        @RequestParam(required = false) SceneType scene,
                        @RequestParam(required = false, defaultValue = "5") @Min(1) @Max(50) Integer limit) {
                if (period == null) {
                        period = getCurrentWeek();
                }
                return ApiResponse.success(dashboardService.getRankings(period, track, scene, limit));
        }

        /** §8.2 GET /dashboard/sourcing-summary - B 軌摘要（依時效落差升冪） */
        @GetMapping("/sourcing-summary")
        public ApiResponse<DashboardSourcingSummaryResponseDto> getSourcingSummary(
                        @RequestParam(required = false, defaultValue = "3") @Min(1) @Max(50) Integer limit) {
                return ApiResponse.success(dashboardService.getSourcingSummary(limit));
        }

        /** §8.2 GET /dashboard/todos - 待辦提示（含待回填結案） */
        @GetMapping("/todos")
        public ApiResponse<DashboardTodosResponseDto> getTodos() {
                return ApiResponse.success(dashboardService.getTodos());
        }

        /** §8.2 GET /dashboard/heat-sources - 熱度來源狀態摘要（不快取） */
        @GetMapping("/heat-sources")
        public ApiResponse<DashboardHeatSourcesResponseDto> getHeatSources() {
                return ApiResponse.success(dashboardService.getHeatSources());
        }

        /** FR-02：預設當前 ISO 週（週一為一週之始、4 日以上屬第一週），格式如 2026W30。 */
        private String getCurrentWeek() {
                LocalDate today = LocalDate.now(TAIPEI_ZONE);
                int week = today.get(WeekFields.ISO.weekOfWeekBasedYear());
                int year = today.get(WeekFields.ISO.weekBasedYear());
                return year + "W" + String.format("%02d", week);
        }
}