package com.example.ssds.ai.prompt;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PromptSanitizerTest {
    private final PromptSanitizer sanitizer = new PromptSanitizer();

    @Test
    void removesPersonalAndOrderIdentifiersBeforePromptAssembly() {
        String sanitized = sanitizer.sanitizeReviewText(
                "王小姐 user@example.com 0912-345-678 訂單 AB12345678，送到台北市信義區松仁路100號，@buyer88");

        assertFalse(sanitized.contains("user@example.com"));
        assertFalse(sanitized.contains("0912-345-678"));
        assertFalse(sanitized.contains("AB12345678"));
        assertFalse(sanitized.contains("松仁路100號"));
        assertFalse(sanitized.contains("buyer88"));
        assertTrue(sanitized.contains("[EMAIL]"));
        assertTrue(sanitized.contains("[PHONE]"));
        assertTrue(sanitized.contains("[ORDER]"));
        assertTrue(sanitized.contains("[ADDRESS]"));
    }
}
