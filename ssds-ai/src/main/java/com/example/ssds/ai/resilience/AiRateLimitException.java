package com.example.ssds.ai.resilience;

public class AiRateLimitException extends RuntimeException {
    public AiRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
