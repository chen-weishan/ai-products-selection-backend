package com.example.ssds.ai.client;

/** 模型識別碼已下架或不存在；Agent 應立即切換備援模型。 */
public class AiModelNotFoundException extends RuntimeException {
    private final String model;

    public AiModelNotFoundException(String model, Throwable cause) {
        super("Mistral 模型不存在或已下架: " + model, cause);
        this.model = model;
    }

    public String model() {
        return model;
    }
}
