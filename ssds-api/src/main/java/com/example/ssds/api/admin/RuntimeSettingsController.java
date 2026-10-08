package com.example.ssds.api.admin;

import com.example.ssds.api.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('SYS_ADMIN')")
public class RuntimeSettingsController {
    private final RuntimeSettingsService settings;

    public RuntimeSettingsController(RuntimeSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/ai-config")
    public ApiResponse<RuntimeSettingsService.AiConfig> aiConfig() {
        return ApiResponse.success(settings.aiConfig());
    }

    @PutMapping("/ai-config")
    public ApiResponse<RuntimeSettingsService.AiConfig> updateAiConfig(
            @RequestBody RuntimeSettingsService.AiConfig request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(settings.updateAi(request, servletRequest.getRemoteAddr()));
    }

    @GetMapping("/schedules")
    public ApiResponse<RuntimeSettingsService.ScheduleConfig> schedules() {
        return ApiResponse.success(settings.schedules());
    }

    @PutMapping("/schedules")
    public ApiResponse<RuntimeSettingsService.ScheduleConfig> updateSchedules(
            @RequestBody RuntimeSettingsService.ScheduleConfig request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(settings.updateSchedules(request, servletRequest.getRemoteAddr()));
    }

    @GetMapping("/operational-config")
    public ApiResponse<RuntimeSettingsService.OperationalConfig> operationalConfig() {
        return ApiResponse.success(settings.operationalConfig());
    }

    @PutMapping("/operational-config")
    public ApiResponse<RuntimeSettingsService.OperationalConfig> updateOperationalConfig(
            @RequestBody RuntimeSettingsService.OperationalConfig request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(settings.updateOperational(request, servletRequest.getRemoteAddr()));
    }
}
