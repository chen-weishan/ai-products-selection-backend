package com.example.ssds.ai.model;

import java.math.BigDecimal;
import java.util.List;

public record SceneClassifierOutput(
        SceneCode sceneType,
        BigDecimal confidence,
        String reasoning,
        SceneCode alternativeScene,
        List<String> signals
) {
    public SceneClassifierOutput {
        signals = signals == null ? List.of() : List.copyOf(signals);
    }
}
