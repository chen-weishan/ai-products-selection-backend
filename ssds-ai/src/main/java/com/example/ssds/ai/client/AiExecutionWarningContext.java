package com.example.ssds.ai.client;

import java.util.ArrayList;
import java.util.List;

/** 在同一個 AI 任務執行緒中，把可恢復的模型設定警示交給任務中心。 */
public final class AiExecutionWarningContext {
    private static final ThreadLocal<List<AiModelUnavailableEvent>> WARNINGS =
            ThreadLocal.withInitial(ArrayList::new);

    private AiExecutionWarningContext() {}

    public static void record(AiModelUnavailableEvent event) {
        WARNINGS.get().add(event);
    }

    public static String consumeMessage() {
        List<AiModelUnavailableEvent> events = WARNINGS.get();
        WARNINGS.remove();
        if (events.isEmpty()) return null;
        return events.stream()
                .map(event -> "%s 的模型 %s 回傳 404，請更新模型設定；系統已切換備援模型。"
                        .formatted(event.modelAlias(), event.model()))
                .distinct()
                .reduce((left, right) -> left + " " + right)
                .orElse(null);
    }

    public static void clear() {
        WARNINGS.remove();
    }
}
