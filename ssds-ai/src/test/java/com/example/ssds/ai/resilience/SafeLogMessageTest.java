package com.example.ssds.ai.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SafeLogMessageTest {
    @Test
    void removesLineBreaksAndLimitsLength() {
        String message = "first\r\n" + "x".repeat(200);

        String sanitized = SafeLogMessage.sanitize(message);

        assertEquals(160, sanitized.length());
        assertEquals("first  ", sanitized.substring(0, 7));
    }

    @Test
    void substitutesUnavailableForNull() {
        assertEquals("unavailable", SafeLogMessage.sanitize(null));
    }
}
