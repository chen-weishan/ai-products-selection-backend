package com.example.ssds.api.calibration;

import com.example.ssds.api.calibration.dto.*;
import com.example.ssds.api.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/calibration/reports")
public class WeightCalibrationController {
    private final WeightCalibrationService service;
    public WeightCalibrationController(WeightCalibrationService service){this.service=service;}
    @PostMapping("/{reportId}/interpretation")
    public ApiResponse<WeightCalibrationResponse> interpret(@PathVariable Long reportId,
            @Valid @RequestBody WeightCalibrationInterpretRequest request){
        return ApiResponse.success(service.interpret(reportId,request));
    }
}
