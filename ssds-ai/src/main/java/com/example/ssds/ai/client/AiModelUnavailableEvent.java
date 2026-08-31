package com.example.ssds.ai.client;

/** 供 audit_log 記錄模型 404，不包含 Prompt、金鑰或模型回應。 */
public record AiModelUnavailableEvent(String modelAlias, String model, int httpStatus) {}
