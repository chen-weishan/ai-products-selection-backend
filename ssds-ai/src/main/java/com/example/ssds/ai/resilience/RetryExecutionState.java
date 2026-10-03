package com.example.ssds.ai.resilience;

import java.util.List;
import java.util.OptionalLong;

//  管理單次 Agent 執行期間所共用的可變重試狀態。
//  * 此類別只負責機械性的重試狀態；各 Agent 專屬的例外映射、降級輸出、
//  * Prompt、解析、快取與預算政策仍保留在各 Agent 內。
public final class RetryExecutionState {
    private final List<String> models;
    private final int rateLimitRetryMax;
    private int modelIndex;
    private int rateLimitRetries;
    private int schemaRetries;
    private int requestCount;
    private String retryInstruction;

    public RetryExecutionState(List<String> models, int rateLimitRetryMax) {
        if (models == null || models.isEmpty()) {
            throw new IllegalArgumentException("At least one model is required");
        }
        this.models = List.copyOf(models);
        this.rateLimitRetryMax = Math.max(0, rateLimitRetryMax);
    }

    public String model() {
        return models.get(modelIndex);
    }

    public boolean isRetryAttempt() {
        return requestCount > 0;
    }

    public void recordRequest() {
        requestCount++;
    }

    public int requestCount() {
        return requestCount;
    }

    public String retryInstruction() {
        return retryInstruction;
    }

    public void retryInstruction(String retryInstruction) {
        this.retryInstruction = retryInstruction;
    }

    //第一次 Schema 驗證失敗後，保留一次使用相同模型重試的機會。
    public boolean beginSchemaRetry() {
        if (schemaRetries != 0) return false;
        schemaRetries++;
        return true;
    }

    //相同模型的 Schema 重試仍失敗後，切換一次至下一個備援模型。
    public boolean moveToSchemaFallback() {
        if (schemaRetries != 1 || !moveToNextModel()) return false;
        schemaRetries++;
        return true;
    }

    //回傳下一次 429 重試的退避時間；達到設定的重試上限後回傳空值。
    public OptionalLong nextRateLimitDelay() {
        if (rateLimitRetries >= rateLimitRetryMax) return OptionalLong.empty();
        long delay = RetryBackoff.delayMillis(rateLimitRetries);
        rateLimitRetries++;
        return OptionalLong.of(delay);
    }

    public int rateLimitRetryCount() {
        return rateLimitRetries;
    }

    public int rateLimitRetryMax() {
        return rateLimitRetryMax;
    }

    public boolean moveToNextModel() {
        if (modelIndex + 1 >= models.size()) return false;
        modelIndex++;
        return true;
    }
}
