package com.example.ssds.ai.routing;

import com.example.ssds.ai.client.AiClientResponse;
import com.example.ssds.ai.client.AiPromptRequest;
import com.example.ssds.ai.client.TrackAAiClient;
import com.example.ssds.core.domain.AiTaskType;
import org.springframework.stereotype.Component;

/** A/B 軌存取分流：A 軌直連 OpenRouter；B 軌不得誤走此固定結構化路徑。 */
@Component
public class AiAccessRouter {
    private final TrackAAiClient trackAClient;

    public AiAccessRouter(TrackAAiClient trackAClient) {
        this.trackAClient = trackAClient;
    }

    public AiClientResponse route(AiPromptRequest request) {
        if (request.taskType().budgetPool() != AiTaskType.BudgetPool.TRACK_A) {
            throw new IllegalArgumentException("非 A 軌任務不得使用 TrackAAiClient: " + request.taskType());
        }
        return trackAClient.complete(request);
    }
}
