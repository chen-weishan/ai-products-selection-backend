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
    private final AiModelOptionsService modelOptions;

    public RuntimeSettingsController(RuntimeSettingsService settings, AiModelOptionsService modelOptions) {
        this.settings = settings;
        this.modelOptions = modelOptions;
    }

    @GetMapping("/ai-config")
    public ApiResponse<RuntimeSettingsService.AiConfig> aiConfig() {
        return ApiResponse.success(settings.aiConfig());
    }

    /** S-14 下拉選項：別名說明與可選模型（不含金鑰）。 */
    @GetMapping("/ai-config/options")
    public ApiResponse<AiModelOptionsService.AiConfigOptions> aiConfigOptions() {
        return ApiResponse.success(modelOptions.options());
    }

    @PutMapping("/ai-config")
    public ApiResponse<RuntimeSettingsService.AiConfig> updateAiConfig(
            @RequestBody RuntimeSettingsService.AiConfig request,
            HttpServletRequest servletRequest) {
        settings.validateAi(request);
        modelOptions.validateReasoningModelsIfChanged(settings.aiConfig(), request);
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

    @GetMapping("/recovery-config")
    public ApiResponse<RuntimeSettingsService.RecoveryConfig> recoveryConfig() {
        return ApiResponse.success(settings.recoveryConfig());
    }

    @PutMapping("/recovery-config")
    public ApiResponse<RuntimeSettingsService.RecoveryConfig> updateRecoveryConfig(
            @RequestBody RuntimeSettingsService.RecoveryConfig request,
            HttpServletRequest servletRequest) {
        return ApiResponse.success(settings.updateRecovery(request, servletRequest.getRemoteAddr()));
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
