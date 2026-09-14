package com.example.ssds.ingest.Instagram;

import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.ingest.HeatDataPoint;
import com.example.ssds.ingest.HeatSourceAdapter;
import com.example.ssds.ingest.Instagram.InstagramHashtagClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Instagram 熱度來源 adapter（品類級，見 §7.2.3 V17 裁決）。
 *
 * <p>target 為 hashtag 名稱（對應 hashtag→品類名稱的設定檔對照，見
 * {@code InstagramHashtagMapping}，由呼叫端在 ssds-api 決定要查哪些品類、
 * 傳進來）。每個 hashtag 查一次「實際抓回的貼文篇數」（見
 * {@link InstagramHashtagClient} 的熱度換算說明——2026-09-07 實測發現該
 * actor 的按讚/留言數固定為 0，改用篇數）當熱度值，單一 hashtag
 * 失敗（含「查無任何貼文」）只記警告並跳過，不影響其餘 hashtag（見
 * {@link HeatSourceAdapter#fetch} 的介面約定）。
 */
@Component
public class InstagramHeatSourceAdapter implements HeatSourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(InstagramHeatSourceAdapter.class);

    private final InstagramHashtagClient client;
    private final InstagramIngestProperties properties;

    InstagramHeatSourceAdapter(InstagramHashtagClient client, InstagramIngestProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public HeatSourceCode sourceCode() {
        return HeatSourceCode.INSTAGRAM;
    }

    @Override
    public List<HeatDataPoint> fetch(List<String> targets, LocalDate date) {
        if (!properties.configured()) {
            throw new IllegalStateException(
                    "Instagram 未設定 Apify token（SSDS_IG_APIFY_TOKEN），無法採集。");
        }

        List<HeatDataPoint> results = new ArrayList<>();
        for (String hashtag : targets) {
            try {
                Long postCount = client.fetchPostCount(hashtag);
                if (postCount == null) {
                    log.warn("Instagram 查無任何貼文，跳過：{}", hashtag);
                    continue;
                }
                results.add(new HeatDataPoint(hashtag, BigDecimal.valueOf(postCount)));
            } catch (Exception e) {
                // 單一 hashtag 失敗不中斷整批，只跳過並記警告——整批一起失敗
                // 才拋例外（見介面說明）。
                log.warn("Instagram hashtag 熱度採集失敗，跳過：{}", hashtag, e);
            }
        }
        return results;
    }
}
