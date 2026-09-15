package com.example.ssds.ingest.Instagram;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Instagram adapter 的 Spring 組態。
 *
 * <p>2026-09-07 改版：改用 Apify 平台的官方 actor
 * {@code apify/instagram-hashtag-scraper}，取代原本的 RapidAPI
 * instagram-social。baseUrl 固定指向 Apify API v2，實際 actor 路徑
 * （含 run-sync-get-dataset-items）由 {@link InstagramHashtagClient} 組裝。
 *
 * <p>與 RapidAPI 版最大的差異：Apify 這支 actor 回傳的是「實際抓到的
 * 貼文明細」（最多 resultsLimit 篇），不是「hashtag 的總貼文數」，因此
 * 熱度值的定義也跟著改變——見 {@link InstagramHashtagClient} 類別註解。
 */
@Configuration
@EnableConfigurationProperties(InstagramIngestProperties.class)
public class InstagramIngestConfig {

    @Bean
    public RestClient instagramRestClient() {
        return RestClient.builder()
                .baseUrl("https://api.apify.com/v2")
                .build();
    }
}
