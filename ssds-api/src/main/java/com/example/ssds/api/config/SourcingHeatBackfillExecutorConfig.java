package com.example.ssds.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 序列化 S-17 回補，避免短時間多筆新增互相覆蓋百分位與合成結果。 */
@Configuration
public class SourcingHeatBackfillExecutorConfig {

    @Bean(name = "sourcingHeatBackfillExecutor")
    public ThreadPoolTaskExecutor sourcingHeatBackfillExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("sourcing-heat-backfill-");
        executor.initialize();
        return executor;
    }
}
