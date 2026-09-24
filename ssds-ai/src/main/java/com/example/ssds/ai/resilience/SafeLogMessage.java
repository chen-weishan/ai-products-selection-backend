package com.example.ssds.ai.resilience;

public final class SafeLogMessage {
    private static final int MAX_LENGTH = 160;

    private SafeLogMessage() {}

    public static String sanitize(String message) {
        if (message == null) return "unavailable";
        String sanitized = message.replace('\r', ' ').replace('\n', ' ');
        return sanitized.length() <= MAX_LENGTH ? sanitized : sanitized.substring(0, MAX_LENGTH);
    }
}
