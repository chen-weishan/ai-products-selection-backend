package com.example.ssds.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** ssds-ai 固定使用 Jackson 2；Spring Boot 4 的 Web JSON 預設已移至 Jackson 3。 */
@Configuration
public class AiJacksonConfiguration {
    @Bean
    public ObjectMapper aiObjectMapper() {
        return new ObjectMapper();
    }
}
