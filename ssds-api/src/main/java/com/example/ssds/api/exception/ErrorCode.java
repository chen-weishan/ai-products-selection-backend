package com.example.ssds.api.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "請求資料驗證失敗"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "尚未登入或憑證無效"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "權限不足"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "找不到指定的資源"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "資源已存在"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "目前狀態不允許此操作"),
    AI_SCHEMA_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "AI 回應格式不正確"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "請求過於頻繁，請稍後再試"),
    BUDGET_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "已超過預算上限"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "系統發生未預期錯誤"),
    AI_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 服務暫時無法使用");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
