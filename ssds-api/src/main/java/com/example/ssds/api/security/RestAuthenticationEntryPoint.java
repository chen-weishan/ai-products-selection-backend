package com.example.ssds.api.security;

import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.common.response.ApiError;
import com.example.ssds.api.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 未登入／憑證無效時的統一回應，維持與 {@link com.example.ssds.api.common.error.GlobalExceptionHandler}
 * 相同的信封格式。這一層無法走 {@code @RestControllerAdvice}——
 * 認證失敗發生在進入 DispatcherServlet 之前，Spring MVC 的例外解析器介入不到。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    // 自建而非注入容器的 ObjectMapper：這裡只序列化一個固定形狀的錯誤 DTO，
    // 不需要應用程式其餘 Jackson 客製設定，也不必依賴該 bean 一定存在於 context。
    // ApiResponse.timestamp 是 OffsetDateTime，findAndRegisterModules() 補上 jsr310 支援。
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {

        boolean expired = Boolean.TRUE.equals(request.getAttribute(JwtAuthenticationFilter.ATTR_TOKEN_EXPIRED));
        ErrorCode code = expired ? ErrorCode.TOKEN_EXPIRED : ErrorCode.UNAUTHORIZED;

        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiError error = new ApiError(code.name(), code.getDefaultMessage());
        objectMapper.writeValue(response.getWriter(), ApiResponse.failure(error));
    }
}