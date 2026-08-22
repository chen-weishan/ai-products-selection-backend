package com.example.ssds.api.common.error;

import com.example.ssds.api.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionUsesConfiguredStatusAndMessage() {
        BusinessException exception = new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND,
                "找不到指定的品項");

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusiness(exception);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().success());
        assertEquals("RESOURCE_NOT_FOUND", response.getBody().error().code());
        assertEquals("找不到指定的品項", response.getBody().error().message());
    }

    @Test
    void unexpectedExceptionDoesNotExposeInternalMessage() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpected(
                new IllegalStateException("database password leaked"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("INTERNAL_ERROR", response.getBody().error().code());
        assertEquals(ErrorCode.INTERNAL_ERROR.getDefaultMessage(), response.getBody().error().message());
    }
}
