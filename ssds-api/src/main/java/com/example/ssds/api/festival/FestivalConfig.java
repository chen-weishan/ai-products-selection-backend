package com.example.ssds.api.festival;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * FR-17 的組態註冊。
 *
 * <p>{@code @ConfigurationProperties} 只是宣告綁定關係，不會自己成為 bean。
 * 本專案的慣例是由一支 {@code @Configuration} 以 {@code @EnableConfigurationProperties}
 * 註冊（見 {@code ssds-ingest} 的三支 {@code *IngestConfig}），漏了這步
 * 會在注入 {@link ClimateProperties} 時啟動失敗。
 */
@Configuration
@EnableConfigurationProperties(ClimateProperties.class)
public class FestivalConfig {
}
