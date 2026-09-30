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

    /**
     * 依資料庫目前記的用量判斷本月額度是否已用完（不連網）。
     * {@code quotaLimit} 為 null 或 ≤ 0 視為無上限，永遠回 false。
     */
    public static boolean isExhausted(HeatSource source) {
        Integer limit = source.getQuotaLimit();
        return limit != null && limit > 0 && source.getQuotaUsed() >= limit;
    }

    /**
     * 採集前的額度護欄：先向 Apify 讀最新用量（免費端點，不消耗爬取額度），再判斷是否還有額度。
     *
     * <p>讀不到用量（未設定 token、Apify 無回應）時<b>放行</b>——額度只是輔助資訊，
     * 且 Apify 帳號本身仍有每月硬上限，不會無限超支。只有「剛讀到的數字」顯示已用完才擋。
     * 讀到的最新用量會寫進 {@code source}，呼叫端記得 save。
     *
     * @return true 表示可以繼續採集
     */
    public static boolean hasRoom(HeatSource source, HeatSourceAdapter adapter) {
        boolean refreshed = refresh(source, adapter);
        return !(refreshed && isExhausted(source));
    }
}
