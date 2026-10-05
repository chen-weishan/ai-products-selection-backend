package com.example.ssds.api.risk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.example.ssds.core.domain.Severity;
import com.example.ssds.core.festival.FestivalWindow;

/**
 * §FR-10-1 {@code FESTIVAL_WINDOW_CLOSING} 判定（純計算，不碰資料庫）。
 *
 * <p>規則：節慶時間窗權重<b>今天是 1.0</b>，且將於<b>門檻日數（預設 7）內</b>降為 0.5 或 0；
 * 呼叫端只餵「該節慶這一輪尚未建立決策」的品項×節慶（A、B 軌皆可）；嚴重度 LOW。
 *
 * <h3>「幾天後會降」怎麼算</h3>
 * 時間窗（{@link FestivalWindow}）在 {@code d ≥ L} 時為 1.0，{@code d < L} 時掉到 0.5（L = 0 時直接是 0）。
 * 今天 d 天後節慶、每過一天 d 減 1，所以第一個權重小於 1.0 的日子是「今天起第 {@code d − L + 1} 天」：
 * <pre>
 *   d = L      → 明天就降（1 天後）
 *   d = L + 6  → 7 天後降      ← 預設門檻的邊界，含
 *   d = L + 7  → 8 天後降      ← 不觸發
 * </pre>
 * 窗權重一律呼叫 {@link FestivalWindow#weight}，不另寫一份不等式。
 *
 * <h3>多節慶</h3>
 * 品項可關聯多個節慶，每個節慶各自判定。同一品項同時有多個符合時只開一筆，
 * 取<b>關聯度最高</b>者（並列取節慶日較早者）——示警是對品項開的、去重也以（品項, 類型）為單位，
 * 同品項開兩筆會被去重成一筆，留下哪一筆不如在這裡明確決定。
 *
 * <h3>略過</h3>
 * 品類沒有前置天數：不假設 0（評分流程缺值時會當 0，但那會讓每個品項在節慶前 6 天都被誤報），
 * 計入 {@code skippedNoLeadTime}。關聯度 ≤ 0 的節慶不判定。
 */
public final class FestivalWindowClosingDetector {

    /** 判定用的一列：品項 × 節慶。 */
    public record Candidate(
            long productId,
            long categoryId,
            Integer leadTimeDays,
            String festivalCode,
            String festivalName,
            LocalDate festivalDate,
            BigDecimal affinity) {
    }

    public record Detection(long productId, String riskType, Severity severity, String triggerValue) {
    }

    /** @param skippedNoLeadTime 因品類沒有前置天數而略過的品項數 */
    public record Result(List<Detection> detections, int skippedNoLeadTime) {
    }

    private static final BigDecimal ONE = new BigDecimal("1.00");

    /**
     * @param today                 偵測日。由呼叫端傳入，本類別不呼叫 {@code LocalDate.now()}
     * @param closingDaysByCategory 各品類的門檻日數（品類覆寫值，缺則全域預設）
     */
    public Result detect(List<Candidate> candidates, LocalDate today, Function<Long, Integer> closingDaysByCategory) {
        Map<Long, List<Candidate>> byProduct = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            byProduct.computeIfAbsent(candidate.productId(), id -> new ArrayList<>()).add(candidate);
        }

        List<Detection> detections = new ArrayList<>();
        int skippedNoLeadTime = 0;
        for (List<Candidate> productCandidates : byProduct.values()) {
            Candidate first = productCandidates.get(0);
            if (first.leadTimeDays() == null) {
                skippedNoLeadTime++;
                continue;
            }
            int threshold = closingDaysByCategory.apply(first.categoryId());

            productCandidates.stream()
                    .filter(candidate -> candidate.affinity() != null && candidate.affinity().signum() > 0)
                    .filter(candidate -> isClosing(candidate, today, threshold))
                    .max(Comparator
                            .comparing(Candidate::affinity)
                            .thenComparing(Candidate::festivalDate, Comparator.reverseOrder()))
                    .ifPresent(candidate -> detections.add(toDetection(candidate, today)));
        }
        return new Result(detections, skippedNoLeadTime);
    }

    /** 今天窗權重為 1.0，且 {@code 門檻} 天內會降。 */
    static boolean isClosing(Candidate candidate, LocalDate today, int thresholdDays) {
        int lead = candidate.leadTimeDays();
        long d = ChronoUnit.DAYS.between(today, candidate.festivalDate());
        if (FestivalWindow.weight(d, lead).compareTo(ONE) != 0) {
            return false;
        }
        return daysUntilDrop(d, lead) <= thresholdDays;
    }

    /** 今天起第幾天的窗權重會首度小於 1.0（明天＝1）。僅對今天權重為 1.0（{@code d ≥ L}）有意義。 */
    static long daysUntilDrop(long daysUntilFestival, int leadTimeDays) {
        return daysUntilFestival - leadTimeDays + 1;
    }

    private static Detection toDetection(Candidate candidate, LocalDate today) {
        int lead = candidate.leadTimeDays();
        long d = ChronoUnit.DAYS.between(today, candidate.festivalDate());
        long inDays = daysUntilDrop(d, lead);
        // 降到多少：前置天數 0 時 d 一旦 < 0 就是節慶已過（0）；否則進入補單期（0.5）
        String after = lead == 0 ? "0" : "0.5";
        String trigger = candidate.festivalName() + " 檔期窗將於 " + inDays + " 日後由 1.0 降為 " + after
                + "（節慶日 " + candidate.festivalDate() + "，前置 " + lead + " 日，關聯度 "
                + candidate.affinity().stripTrailingZeros().toPlainString() + "）";
        return new Detection(candidate.productId(), RiskTypes.FESTIVAL_WINDOW_CLOSING, Severity.LOW, trigger);
    }
}
