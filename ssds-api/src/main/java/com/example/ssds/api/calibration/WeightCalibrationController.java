package com.example.ssds.api.calibration;

import com.example.ssds.api.aitask.AiTaskService;
import com.example.ssds.api.aitask.dto.*;
import com.example.ssds.api.calibration.dto.*;
import com.example.ssds.api.common.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/calibration/reports")
public class WeightCalibrationController {
    private final AiTaskService taskService;
    public WeightCalibrationController(AiTaskService taskService){this.taskService=taskService;}
    @PostMapping("/{reportId}/interpretation")
    public ResponseEntity<ApiResponse<AiTaskResponse>> interpret(
            @PathVariable("reportId") Long reportId,
            @Valid @RequestBody WeightCalibrationTaskRequest request){
        AiTaskResponse task = taskService.create(new CreateAiTaskRequest(
                com.example.ssds.core.domain.AiTaskType.WEIGHT_CALIBRATION,
                List.of(),
                List.of(),
                List.of(reportId),
                new CreateAiTaskRequest.Options(request.forceRefresh())));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(task));
    }
}
