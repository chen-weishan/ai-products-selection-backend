package com.example.ssds.api.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 是後續 TypeScript client 與 Postman 匯入的單一契約來源。 */
@Configuration
@OpenAPIDefinition(info = @Info(
        title = "SSDS API",
        version = "3.0",
        description = "選品決策系統後端 API"))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfig {}
