package com.example.ssds.ai.client;

public class AiRateLimitException extends RuntimeException {
    public AiRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
