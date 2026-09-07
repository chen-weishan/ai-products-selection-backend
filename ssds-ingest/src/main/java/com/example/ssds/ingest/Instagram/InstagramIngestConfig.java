package com.example.ssds.ingest.Instagram;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Instagram adapter 的 Spring 組態。
 *
 * <p>2026-09-01 改版：改用 RapidAPI 的 instagram-social 服務，見
 * {@link InstagramIngestProperties} 的類別註解。這支服務一次查詢就直接
 * 回傳 hashtag 的 media_count，不需要「先查 hashtag_id 再查 media」的
 * 兩步式流程，也沒有 Meta 官方版「7 天 30 個新 hashtag」的限制，
 * 因此原本用來快取 hashtag_id 的 Caffeine CacheManager 一併拿掉，
 * 架構單純很多。
 *
 * <p>唯一要注意的新限制：RapidAPI 免費方案是 <b>總共 100 次請求</b>
 * （不是每天，reset 週期看起來約 30 天），見
 * {@code InstagramHeatIngestJob} 的排程頻率說明。
 */
@Configuration
@EnableConfigurationProperties(InstagramIngestProperties.class)
public class InstagramIngestConfig {

    @Bean
    public RestClient instagramRestClient() {
        return RestClient.builder()
                .baseUrl("https://instagram-social.p.rapidapi.com")
                .build();
    }
}