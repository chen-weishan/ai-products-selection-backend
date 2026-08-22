package com.example.ssds.api.common.error;

import org.springframework.http.HttpStatus;

import lombok.Getter;

/**
 * 規格書 §8.1 定義的 API 錯誤代碼。
 *
 * <p>每個代碼綁定固定的 HTTP 狀態與預設訊息。預設訊息面向前端使用者，
 * 不得包含技術細節（SQL、堆疊、內部類別名），需要細節時由呼叫端另外傳 message。
 */
@Getter
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "欄位驗證失敗，請檢查輸入內容"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "尚未登入或憑證無效，請重新登入"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "登入已逾時，請重新取得授權"),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "登入憑證已失效或遭撤銷，請重新登入"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "權限不足，無法執行此操作"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "找不到指定的資料"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "資料已存在，不可重複建立"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "目前狀態不允許此操作"),
    WEIGHT_SUM_INVALID(HttpStatus.CONFLICT, "情境權重組加總必須等於 1.000"),
    AI_SCHEMA_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "AI 回應格式不正確，請稍後再試"),
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "連續登入失敗次數過多，帳號已鎖定"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "請求過於頻繁，請稍後再試"),
    QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "已達預算配額上限，請聯絡管理員"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "系統發生未預期的錯誤，請稍後再試"),
    AI_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 服務暫時無法使用，請稍後再試"),
    SCOUT_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "尋源服務暫時無法使用，請稍後再試");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}
