package com.example.ssds.controller;

import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.dto.DashboardSummaryDto;
import com.example.ssds.api.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.Locale;

@RestController
@RequestMapping("api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/summary")
    public ApiResponse<DashboardSummaryDto> getSummary(
            @RequestParam(required = false) String period,
            @RequestParam(required = false, defaultValue = "A") String track) {
        if (period == null) {
            period = getCurrentWeek();
        }
        return ApiResponse.success(dashboardService.getSummary(period, track));
    }

    private String getCurrentWeek() {
        LocalDate now = LocalDate.now();
        int week = now.get(WeekFields.of(Locale.TAIWAN).weekOfWeekBasedYear());
        int year = now.get(WeekFields.of(Locale.TAIWAN).weekBasedYear());
        return year + "W" + String.format("%02d", week);
    }
}