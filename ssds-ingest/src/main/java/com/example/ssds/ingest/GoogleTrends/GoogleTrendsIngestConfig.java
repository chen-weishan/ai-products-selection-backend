package com.example.ssds.ingest.GoogleTrends;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(GoogleTrendsIngestProperties.class)
public class GoogleTrendsIngestConfig {

    @Bean
    public RestClient googleTrendsRestClient() {
        return RestClient.builder()
                .baseUrl("https://api.apify.com/v2")
                .build();
    }
}