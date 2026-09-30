package com.example.ssds.ingest.GoogleTrends;

import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.ingest.ApifyTokenProbe;
import com.example.ssds.ingest.ApifyUsage;
import com.example.ssds.ingest.ApifyUsageProbe;
import com.example.ssds.ingest.HeatDataPoint;
import com.example.ssds.ingest.HeatSourceAdapter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GoogleTrendsHeatSourceAdapter implements HeatSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(GoogleTrendsHeatSourceAdapter.class);

    private final GoogleTrendsClient client;
    private final GoogleTrendsIngestProperties properties;

    GoogleTrendsHeatSourceAdapter(GoogleTrendsClient client, GoogleTrendsIngestProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public HeatSourceCode sourceCode() {
        return HeatSourceCode.GOOGLE_TRENDS;
    }

    @Override
    public List<HeatDataPoint> fetch(List<String> targets, LocalDate date) {
        if (!properties.configured()) {
            throw new IllegalStateException(
                    "Google Trends 未設定 Apify token（SSDS_TRENDS_APIFY_TOKEN），無法採集。");
        }

        List<HeatDataPoint> results = new ArrayList<>();
        for (String keyword : targets) {
            try {
                Long interest = client.fetchLatestInterest(keyword);
                if (interest == null) {
                    log.warn("Google Trends 查無資料，跳過：{}", keyword);
                    continue;
                }
                results.add(new HeatDataPoint(keyword, BigDecimal.valueOf(interest)));
            } catch (Exception e) {
                log.warn("Google Trends 關鍵字熱度採集失敗，跳過：{}", keyword, e);
            }
        }
        return results;
    }

    /**
     * 只驗證 Apify token 有效、Apify 連得上（{@code GET /v2/users/me}），
     * 不執行任何 actor、不消耗爬取額度。未設定 token 直接回 false。
     */
    @Override
    public boolean probe() {
        return ApifyTokenProbe.tokenIsValid(properties.apifyToken());
    }

    /** 讀取此 token 所屬 Apify 帳號本月用量與上限（{@code GET /v2/users/me/limits}），不消耗爬取額度。 */
    @Override
    public Optional<ApifyUsage> fetchQuota() {
        return ApifyUsageProbe.fetch(properties.apifyToken());
    }
}
