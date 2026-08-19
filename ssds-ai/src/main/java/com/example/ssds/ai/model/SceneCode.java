package com.example.ssds.ai.model;

import com.example.ssds.core.domain.SceneType;

/** 對外 AI/API 契約；把既有資料庫的 STAPLE_RESTOCK 映射成規格用語 REPLENISHMENT。 */
public enum SceneCode {
    VIRAL_TOPIC,
    FESTIVAL,
    REPLENISHMENT,
    SEASONAL;

    public SceneType toDomain() {
        return switch (this) {
            case VIRAL_TOPIC -> SceneType.VIRAL_TOPIC;
            case FESTIVAL -> SceneType.FESTIVAL;
            case REPLENISHMENT -> SceneType.STAPLE_RESTOCK;
            case SEASONAL -> SceneType.SEASONAL;
        };
    }

    public static SceneCode fromDomain(SceneType value) {
        return switch (value) {
            case VIRAL_TOPIC -> VIRAL_TOPIC;
            case FESTIVAL -> FESTIVAL;
            case STAPLE_RESTOCK -> REPLENISHMENT;
            case SEASONAL -> SEASONAL;
        };
    }

    public static SceneCode parse(String value) {
        if ("VIRAL".equals(value)) {
            return VIRAL_TOPIC;
        }
        if ("STAPLE_RESTOCK".equals(value)) {
            return REPLENISHMENT;
        }
        return valueOf(value);
    }
}
