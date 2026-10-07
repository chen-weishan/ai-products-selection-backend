package com.example.ssds.api.calibration;

import com.example.ssds.api.aitask.dto.CreateAiTaskRequest;
import com.example.ssds.api.aitask.service.AiTaskService;
import com.example.ssds.core.domain.AiTaskType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * §FR-15 步驟 2：為校準報告建立 Agent 7 解讀任務。季初排程與 S-19 手動重算共用。
 *
 * <p>必須在產生報告的交易提交之後呼叫：{@code AiTaskService.create} 會加入呼叫端的交易，
 * 在交易內失敗會把整筆標成 rollback-only，連報告一起回滾。
 * 任務本身由 {@code AiTaskWorker} 於提交後非同步執行。
 */
@Component
@RequiredArgsConstructor
class CalibrationInterpretations {

    private static final Logger log = LoggerFactory.getLogger(CalibrationInterpretations.class);

    private final AiTaskService aiTaskService;

    /**
     * @return 解讀任務 id；建立失敗時回 null（報告已落盤，不受影響，可經
     *         {@code POST /calibration/reports/{id}/interpretation} 補觸發）
     */
    Long request(Long reportId, String quarter) {
        try {
            var task = aiTaskService.create(new CreateAiTaskRequest(
                    AiTaskType.WEIGHT_CALIBRATION, List.of(), List.of(), List.of(reportId),
                    new CreateAiTaskRequest.Options(false)));
            log.info("Calibration interpretation task created: quarter={}, reportId={}, taskId={}",
                    quarter, reportId, task.taskId());
            return task.taskId();
        } catch (RuntimeException e) {
            log.warn("Calibration interpretation task not created: quarter={}, reportId={}, reason={}",
                    quarter, reportId, e.getMessage());
            return null;
        }
    }
}
