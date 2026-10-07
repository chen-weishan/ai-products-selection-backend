package com.example.ssds.api.report.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ReportEventStreamServiceTest {

    @Test
    void createsSeparatePageScopedStreamsForEachUser() {
        ReportEventStreamService service = new ReportEventStreamService(new ObjectMapper());

        SseEmitter first = service.subscribe("first@example.com");
        SseEmitter second = service.subscribe("second@example.com");

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(1, service.subscriberCount("first@example.com"));
        assertEquals(1, service.subscriberCount("second@example.com"));
        assertEquals(0, service.subscriberCount("missing@example.com"));
    }
}
