package com.example.ssds.ingest.Threads;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Threads adapter 的 Spring 組態。用 Apify 平台的第三方 actor
 * easyapi/threads-search-scraper 查關鍵字搜尋結果，比照 Instagram 的
 * InstagramIngestConfig 作法。
 */
@Configuration
@EnableConfigurationProperties(ThreadsIngestProperties.class)
public class ThreadsIngestConfig {

    @Bean
    public RestClient threadsRestClient() {
        return RestClient.builder()
                .baseUrl("https://api.apify.com/v2")
                .build();
    }
}