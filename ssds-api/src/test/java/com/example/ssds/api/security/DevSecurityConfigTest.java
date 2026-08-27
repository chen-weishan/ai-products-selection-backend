package com.example.ssds.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class DevSecurityConfigTest {

    @Test
    void corsAllowsAngularDevServerAndAuthorizationHeader() {
        CorsConfigurationSource source = new DevSecurityConfig()
                .devCorsConfigurationSource("http://localhost:4200");
        HttpServletRequest request = new MockHttpServletRequest(
                "OPTIONS",
                "/products"
        );

        CorsConfiguration configuration = source.getCorsConfiguration(request);

        assertNotNull(configuration);
        assertEquals(
                List.of("http://localhost:4200"),
                configuration.getAllowedOrigins()
        );
        assertTrue(configuration.getAllowedMethods().contains("OPTIONS"));
        assertTrue(configuration.getAllowedMethods().contains("GET"));
        assertTrue(configuration.getAllowedHeaders().contains(
                HttpHeaders.AUTHORIZATION
        ));
        assertEquals(Boolean.TRUE, configuration.getAllowCredentials());
    }

    @Test
    void corsSupportsMultipleConfiguredOrigins() {
        CorsConfigurationSource source = new DevSecurityConfig()
                .devCorsConfigurationSource(
                        "http://localhost:4200, http://127.0.0.1:4200"
                );

        CorsConfiguration configuration = source.getCorsConfiguration(
                new MockHttpServletRequest("OPTIONS", "/products")
        );

        assertNotNull(configuration);
        assertEquals(
                List.of(
                        "http://localhost:4200",
                        "http://127.0.0.1:4200"
                ),
                configuration.getAllowedOrigins()
        );
    }
}
