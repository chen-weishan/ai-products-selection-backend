package com.example.ssds.api.heat;

import com.example.ssds.infra.entity.HeatSource;
import com.example.ssds.ingest.ApifyUsage;
import com.example.ssds.ingest.HeatSourceAdapter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把 Apify 後台的本月用量寫回 {@code heat_source.quota_used／quota_limit}（S-16「額度用量」欄）。
 *
 * <p>單位是<b>美分</b>（見 {@link ApifyUsage}），數值直接取自 Apify 帳號，不再由採集排程
 * 自行累加關鍵字數——兩種單位混用會讓數字失去意義。Apify 換月時自己重置，這裡不需要歸零。
 *
 * <p>查不到用量（未設定 token、Apify 無回應）時保留原值，不拋例外：額度顯示是輔助資訊，
 * 不能因此讓測試連線或每日採集失敗。
 */
public final class HeatSourceQuota {

    private static final Logger log = LoggerFactory.getLogger(HeatSourceQuota.class);

    private HeatSourceQuota() {
    }

    /** @return 是否真的更新了數值 */
    public static boolean refresh(HeatSource source, HeatSourceAdapter adapter) {
        Optional<ApifyUsage> usage;
        try {
            usage = adapter.fetchQuota();
        } catch (RuntimeException e) {
            log.warn("讀取 {} 的 Apify 額度用量失敗，保留原值。", source.getSourceCode(), e);
            return false;
        }
        if (usage == null || usage.isEmpty()) {
            return false;
        }
        source.setQuotaUsed(usage.get().usedCents());
        source.setQuotaLimit(usage.get().limitCents());
        return true;
    }
}
