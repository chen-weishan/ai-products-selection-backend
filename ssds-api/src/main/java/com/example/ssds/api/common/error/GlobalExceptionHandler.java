package com.example.ssds.api.common.error;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.example.ssds.api.common.response.ApiError;
import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.api.common.response.FieldError;

import jakarta.validation.ConstraintViolationException;

/**
 * 將所有例外統一轉換為規格書 §8.1 的錯誤格式。
 * 全專案只允許存在這一個 @RestControllerAdvice。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 業務規則違反：錯誤碼與訊息都由拋出端決定。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(
            BusinessException exception
    ) {
        return toResponse(
                exception.getErrorCode(),
                exception.getMessage(),
                exception.getFieldErrors()
        );
    }

    /** @Valid @RequestBody 驗證失敗。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception
    ) {
        List<FieldError> fieldErrors =
                exception.getBindingResult()
                        .getFieldErrors()
                        .stream()
                        .map(error -> new FieldError(
                                error.getField(),
                                error.getDefaultMessage() == null
                                        ? "欄位格式不正確"
                                        : error.getDefaultMessage()
                        ))
                        .distinct()
                        .toList();

        return toResponse(
                ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                fieldErrors
        );
    }

    /** @Validated 加在 query parameter 或 path variable 上的驗證失敗。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException exception
    ) {
        List<FieldError> fieldErrors =
                exception.getConstraintViolations()
                        .stream()
                        .map(violation -> new FieldError(
                                violation.getPropertyPath().toString(),
                                violation.getMessage()
                        ))
                        .toList();

        return toResponse(
                ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                fieldErrors
        );
    }

    /** 型別轉換失敗，例如 ?page=abc。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception
    ) {
        FieldError fieldError = new FieldError(
                exception.getName(),
                "參數格式不正確"
        );

        return toResponse(
                ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.getDefaultMessage(),
                List.of(fieldError)
        );
    }

    /** Request body 不是合法 JSON，或欄位型別不符。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(
            HttpMessageNotReadableException exception
    ) {
        log.warn("無法解析的請求內容: {}", exception.getMessage());

        return toResponse(
                ErrorCode.VALIDATION_FAILED,
                "請求內容格式不正確",
                null
        );
    }

    /** Spring Security 判定權限不足。 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
            AccessDeniedException exception
    ) {
        return toResponse(
                ErrorCode.FORBIDDEN,
                ErrorCode.FORBIDDEN.getDefaultMessage(),
                null
        );
    }

    /** 兜底：處理未被其他方法攔截的例外。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(
            Exception exception
    ) {
        log.error("未預期的錯誤", exception);

        return toResponse(
                ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.getDefaultMessage(),
                null
        );
    }

    private ResponseEntity<ApiResponse<Void>> toResponse(
            ErrorCode code,
            String message,
            List<FieldError> fieldErrors
    ) {
        ApiError error = new ApiError(
                code.name(),
                message,
                fieldErrors
        );

        return ResponseEntity
                .status(code.getHttpStatus())
                .body(ApiResponse.failure(error));
    }
}