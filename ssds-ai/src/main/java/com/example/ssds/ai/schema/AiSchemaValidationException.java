package com.example.ssds.ai.schema;

public class AiSchemaValidationException extends RuntimeException {
    public AiSchemaValidationException(String message) {
        super(message);
    }

    public AiSchemaValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
