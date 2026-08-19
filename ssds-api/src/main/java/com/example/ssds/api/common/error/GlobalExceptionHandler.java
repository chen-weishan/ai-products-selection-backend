package com.example.ssds.api.common.error;

import com.example.ssds.api.common.response.ApiErrorResponse;
import com.example.ssds.api.common.response.ApiErrorResponse.FieldError;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 將應用程式例外統一轉換為規格書 §8.1 的錯誤格式。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 處理應用程式主動拋出的 API 例外。 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
        ApiErrorCode code = exception.getCode();
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiErrorResponse.of(
                        code, exception.getMessage(), exception.getFieldErrors()));
    }

    /** 處理 @Valid RequestBody 驗證失敗。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception) {
        List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(
                        error.getField(),
                        error.getDefaultMessage() == null
                                ? "欄位格式不正確"
                                : error.getDefaultMessage()))
                .distinct()
                .toList();

        return validationError("請求欄位驗證失敗", fieldErrors);
    }

    /** 處理 @ModelAttribute 或表單物件綁定失敗。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiErrorResponse> handleBindException(BindException exception) {
        List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(
                        error.getField(),
                        error.getDefaultMessage() == null
                                ? "欄位格式不正確"
                                : error.getDefaultMessage()))
                .distinct()
                .toList();

        return validationError("查詢參數驗證失敗", fieldErrors);
    }

    /** 處理 query parameter、path variable 的型別轉換失敗。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception) {
        FieldError fieldError = new FieldError(
                exception.getName(), "參數格式或允許值不正確");
        return validationError("查詢參數驗證失敗", List.of(fieldError));
    }

    /** 處理 Jakarta Validation 的 constraint violation。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception) {
        List<FieldError> fieldErrors = exception.getConstraintViolations().stream()
                .map(violation -> new FieldError(
                        violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return validationError("查詢參數驗證失敗", fieldErrors);
    }

    /** 處理 JSON 格式、JSON enum 值或 RequestBody 解析錯誤。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMessageNotReadable(
            HttpMessageNotReadableException exception) {
        return validationError("請求內容格式不正確", List.of());
    }

    /** 處理找不到指定資源。 */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleEntityNotFound(
            EntityNotFoundException exception) {
        ApiErrorCode code = ApiErrorCode.RESOURCE_NOT_FOUND;
        String message = exception.getMessage() == null
                ? "找不到指定的資源"
                : exception.getMessage();
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiErrorResponse.of(code, message));
    }

    /** 處理唯一鍵、外鍵與其他資料完整性衝突。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException exception) {
        log.warn("資料完整性衝突", exception);

        ApiErrorCode code = ApiErrorCode.DUPLICATE_RESOURCE;
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiErrorResponse.of(
                        code, "資料重複或關聯狀態不允許此操作"));
    }

    /** 處理方法層級的權限不足，例如 @PreAuthorize。 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException exception) {
        ApiErrorCode code = ApiErrorCode.FORBIDDEN;
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiErrorResponse.of(code, "沒有權限執行此操作"));
    }

    /** 處理一般參數或業務前置條件錯誤。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
            IllegalArgumentException exception) {
        String message = exception.getMessage() == null
                ? "請求參數不正確"
                : exception.getMessage();
        return validationError(message, List.of());
    }

    /** 最後一道防線，不將 stack trace 或內部訊息回傳給使用者。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception) {
        log.error("未預期的伺服器錯誤", exception);

        ApiErrorCode code = ApiErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiErrorResponse.of(code, "系統發生未預期錯誤"));
    }

    private ResponseEntity<ApiErrorResponse> validationError(
            String message, List<FieldError> fieldErrors) {
        ApiErrorCode code = ApiErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(code.getHttpStatus())
                .body(ApiErrorResponse.of(code, message, fieldErrors));
    }
}
