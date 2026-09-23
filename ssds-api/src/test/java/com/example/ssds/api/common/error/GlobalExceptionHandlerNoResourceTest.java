package com.example.ssds.api.common.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssds.api.common.response.ApiResponse;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 路徑打錯要回 404，不是 500。
 *
 * <p>2026-09-22 的實例：從文件複製網址時帶進零寬空格 U+200B，路徑變成
 * {@code /api/v1/\u200b\u200bclimate-normals\u200b}，被兜底 handler 吃成
 * 「系統發生未預期的錯誤」，完全看不出是自己網址打錯。
 */
class GlobalExceptionHandlerNoResourceTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unknownPathIsNotFoundNotInternalError() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleNoResourceFound(
                // Boot 4／Spring 7 的建構子是三參數（method, resourcePath, requestPath），
                // Boot 3 只有兩個——網路上的範例多半是舊的
                new NoResourceFoundException(
                        HttpMethod.GET,
                        "\u200b\u200bclimate-normals\u200b",
                        "/api/v1/\u200b\u200bclimate-normals\u200b"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND.name(), response.getBody().error().code());
        assertTrue(response.getBody().error().message().contains("climate-normals"),
                "訊息要帶上實際路徑，隱形字元才有機會現形");
    }
}
