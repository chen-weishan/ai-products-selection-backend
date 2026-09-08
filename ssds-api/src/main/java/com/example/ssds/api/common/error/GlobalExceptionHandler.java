package com.example.ssds.api.common.error;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.ssds.api.common.response.ApiError;
import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.common.response.FieldError;

import jakarta.validation.ConstraintViolationException;

/** 將所有例外統一轉換為規格書 §8.1 的錯誤格式。全專案只允許存在這一個 @RestControllerAdvice。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 業務規則違反：錯誤碼與訊息都由拋出端決定。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        return toResponse(e.getErrorCode(), e.getMessage(), e.getFieldErrors());
    }

    /** @Valid @RequestBody 驗證失敗。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e) {
        List<FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldError(
                        fe.getField(),
                        fe.getDefaultMessage() == null ? "欄位格式不正確" : fe.getDefaultMessage()))
                .distinct()
                .toList();
        return toResponse(ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.getDefaultMessage(), fieldErrors);
    }

    /** @Validated 加在 query parameter 或 path variable 上的驗證失敗。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException e) {
        List<FieldError> fieldErrors = e.getConstraintViolations().stream()
                .map(v -> new FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return toResponse(ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.getDefaultMessage(), fieldErrors);
    }

    /**
     * 必填的 query parameter 沒帶，例如 /scores/ranking 少了 period。
     *
     * <p>不單獨攔會被兜底吃成 500：本類別沒有繼承
     * {@code ResponseEntityExceptionHandler}，所以 Spring 對這個例外的預設 400
     * 處理不會生效。客戶端少帶參數屬於 400，不是伺服器錯誤。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(
            MissingServletRequestParameterException e) {
        FieldError fieldError = new FieldError(e.getParameterName(), "必填參數未提供");
        return toResponse(ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.getDefaultMessage(), List.of(fieldError));
    }

    /**
     * 路徑對但 HTTP 方法不對，例如對 {@code POST /scores/simulate} 發 GET。
     *
     * <p>不單獨攔會被兜底吃成 500，與 {@link #handleMissingParameter} 同一類問題：
     * 本類別沒有繼承 {@code ResponseEntityExceptionHandler}，Spring 的預設 405 處理不生效。
     *
     * <p>回應帶 {@code Allow} 標頭列出實際支援的方法（RFC 7231 §6.5.5 要求 405 必須帶）。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e) {

        ApiError error = new ApiError(
                ErrorCode.METHOD_NOT_ALLOWED.name(),
                ErrorCode.METHOD_NOT_ALLOWED.getDefaultMessage(),
                null);

        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus());

        Set<HttpMethod> supported = e.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            builder.allow(supported.toArray(new HttpMethod[0]));
        }
        return builder.body(ApiResponse.failure(error));
    }

    /** 型別轉換失敗，例如 ?page=abc。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException e) {
        FieldError fieldError = new FieldError(e.getName(), "參數格式不正確");
        return toResponse(ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.getDefaultMessage(), List.of(fieldError));
    }

    /** request body 不是合法 JSON，或欄位型別對不上。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("無法解析的請求內容: {}", e.getMessage());
        return toResponse(ErrorCode.VALIDATION_FAILED, "請求內容格式不正確", null);
    }

    /** Spring Security 判定權限不足。不單獨攔會被兜底吃成 500。 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return toResponse(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getDefaultMessage(), null);
    }

    /** 兜底：任何沒被上面攔到的例外。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("未預期的錯誤", e);
        return toResponse(ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.getDefaultMessage(), null);
    }

    private ResponseEntity<ApiResponse<Void>> toResponse(
            ErrorCode code, String message, List<FieldError> fieldErrors) {
        ApiError error = new ApiError(code.name(), message, fieldErrors);
        return ResponseEntity.status(code.getHttpStatus()).body(ApiResponse.failure(error));
    }
}