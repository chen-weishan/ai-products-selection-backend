package com.example.ssds.ai.model;

import com.example.ssds.core.domain.SceneType;

/** 對外 AI/API 契約；與領域列舉分離，讓外部契約可獨立演進。 */
public enum SceneCode {
    VIRAL,
    FESTIVAL,
    REPLENISHMENT,
    SEASONAL;

    public SceneType toDomain() {
        return switch (this) {
            case VIRAL -> SceneType.VIRAL;
            case FESTIVAL -> SceneType.FESTIVAL;
            case REPLENISHMENT -> SceneType.REPLENISHMENT;
            case SEASONAL -> SceneType.SEASONAL;
        };
    }

    public static SceneCode fromDomain(SceneType value) {
        return switch (value) {
            case VIRAL -> VIRAL;
            case FESTIVAL -> FESTIVAL;
            case REPLENISHMENT -> REPLENISHMENT;
            case SEASONAL -> SEASONAL;
        };
    }

    public static SceneCode parse(String value) {
        return valueOf(value);
    }
}
