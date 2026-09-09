package com.example.ssds.api.score;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;

import com.example.ssds.api.score.dto.ScoreDeductionsResponse;
import com.example.ssds.api.score.dto.ScoreDetailResponse;
import com.example.ssds.api.score.dto.ScoreFactorBarResponse;
import com.example.ssds.api.score.dto.ScoreHistoryPointResponse;
import com.example.ssds.api.score.dto.ScoreRankingRowResponse;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.FactorCode;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.ScoreFactor;

/**
 * entity → 回應 DTO 的轉換。純函式、無狀態，因此是 static 而非 Spring bean
 * （與 {@code WeightVersionMapper} 同一個做法）。
 */
public final class ScoreMapper {

    /** §8.1：回應時間一律以 +08:00 呈現。 */
    private static final ZoneId API_ZONE = ZoneId.of("Asia/Taipei");

    private ScoreMapper() {
        throw new AssertionError("工具類別，不應被實例化");
    }

    /**
     * @param page             已分頁的分數，product／category 必須已 join fetch
     * @param factorsByScoreId 這一頁全部分數的因子，依 score id 分組
     */
    public static Page<ScoreRankingRowResponse> toRankingRows(
            Page<ProductScore> page, Map<Long, List<ScoreFactor>> factorsByScoreId) {
        // Page.map 保留分頁中繼資料（totalElements、totalPages），
        // 用 getContent().stream() 重組會把那些資訊弄丟
        return page.map(score -> toRow(score, factorsByScoreId));
    }

    private static ScoreRankingRowResponse toRow(
            ProductScore score, Map<Long, List<ScoreFactor>> factorsByScoreId) {

        // 只取加分因子：扣分明細是獨立卡片（§FR-04「扣分明細以獨立卡片呈現」），
        // 由 GET /scores/{id}/deductions 另外供應，不塞在排行列裡
        List<ScoreFactorBarResponse> bars = factorsByScoreId
                .getOrDefault(score.getId(), List.of())
                .stream()
                .filter(f -> !f.isPenalty())
                .map(ScoreMapper::toBar)
                .toList();

        // 分數三欄取資料庫的既有值
        return buildRankingRow(score, bars,
                score.getBonusSubtotal(), score.getFinalScore(), score.getGrade());
    }

    /**
     * 組出排行列。{@code toRow}（讀資料庫既有分數）與 {@code toSimulatedRow}（讀重算值）
     * 共用這一份，差別只有 bonus／final／grade 三欄由參數決定。
     *
     * <p>兩處各寫一份 14 個參數的建構子呼叫時，之後加欄位容易漏改其中一邊。
     *
     * <p>{@code penaltySubtotal} 恆取 entity 的原值——扣分因子固定生效、
     * 不參與權重調整（§5.2.2），換權重版本不影響它。
     */
    private static ScoreRankingRowResponse buildRankingRow(
            ProductScore score,
            List<ScoreFactorBarResponse> bars,
            BigDecimal bonusSubtotal,
            BigDecimal finalScore,
            Grade grade) {

        Category category = score.getProduct().getCategory();

        return new ScoreRankingRowResponse(
                score.getId(),
                score.getProduct().getId(),
                score.getProduct().getName(),
                category == null ? null : category.getName(),
                score.getSceneType(),
                score.isPrimary(),
                bonusSubtotal,
                // 正值直接回，負號由 UI 加（AC-04-7）
                score.getPenaltySubtotal(),
                finalScore,
                grade,
                score.getConfidence(),
                score.isLowConfidence(),
                score.isRiskSuppressed(),
                bars);
    }

    private static ScoreFactorBarResponse toBar(ScoreFactor factor) {
        return new ScoreFactorBarResponse(
                factor.getFactorCode(),
                factor.getNormalizedValue(),
                factor.getWeight(),
                factor.isDataAvailable(),
                factor.isImputed());
    }

    /**
     * 扣分明細（§FR-04「扣分明細以獨立卡片呈現」）。
     *
     * <p>小計直接取 {@code score.getPenaltySubtotal()}，<b>不是</b>把
     * {@code penaltyFactors} 加總——兩者不保證一致，見
     * {@link ScoreDeductionsResponse} 的說明。
     *
     * @param penaltyFactors 只該包含扣分因子，由
     *                       {@code findByScoreIdAndPenalty(id, true)} 取得
     */
    public static ScoreDeductionsResponse toDeductions(
            ProductScore score, List<ScoreFactor> penaltyFactors) {

        // 依 FactorCode 宣告順序排：REVIEW_RISK → LOGISTICS_RISK → INVENTORY_RISK，
        // 與規格 §5.5 計算範例的扣分項表格順序一致。
        // 不排的話每次查詢的卡片順序可能不同，畫面會跳動。
        List<ScoreDeductionsResponse.DeductionItem> items = penaltyFactors.stream()
                .sorted(Comparator.comparing(ScoreFactor::getFactorCode))
                .map(ScoreMapper::toDeductionItem)
                .toList();

        return new ScoreDeductionsResponse(
                score.getId(),
                // 正值直接回，負號由 UI 加（AC-04-7）
                score.getPenaltySubtotal(),
                score.isRiskSuppressed(),
                items);
    }

    private static ScoreDeductionsResponse.DeductionItem toDeductionItem(ScoreFactor factor) {
        return new ScoreDeductionsResponse.DeductionItem(
                factor.getFactorCode(),
                factor.getPenaltyValue(),
                factor.getRawValue(),
                factor.isDataAvailable());
    }

    /**
     * 單一品項的分數快照（§8.2 GET /products/{id}/scores，含九項因子明細）。
     *
     * <p>加分與扣分拆成兩個清單，不混在一起——§FR-04 要求扣分明細獨立成卡片。
     *
     * <p>兩邊都要排序：{@code FactorCode} 的宣告順序就是 §FR-04 指定的顯示順序
     * （加分 TREND→MARGIN→CVR→PRICE_FIT→FESTIVAL→CLIMATE，
     * 扣分 REVIEW_RISK→LOGISTICS_RISK→INVENTORY_RISK）。
     * 沒有排序的話每次查詢的長條順序可能不同，畫面會跳動。
     *
     * @param factors 該筆分數的<b>全部</b>九個因子，由
     *                {@code ScoreFactorRepository.findByScoreId(...)} 取得
     */
    public static ScoreDetailResponse toDetail(ProductScore score, List<ScoreFactor> factors) {

        List<ScoreFactorBarResponse> bonusFactors = factors.stream()
                .filter(f -> !f.isPenalty())
                .sorted(Comparator.comparing(ScoreFactor::getFactorCode))
                .map(ScoreMapper::toBar)
                .toList();

        List<ScoreDeductionsResponse.DeductionItem> penaltyFactors = factors.stream()
                .filter(ScoreFactor::isPenalty)
                .sorted(Comparator.comparing(ScoreFactor::getFactorCode))
                .map(ScoreMapper::toDeductionItem)
                .toList();

        Category category = score.getProduct().getCategory();

        return new ScoreDetailResponse(
                score.getId(),
                score.getProduct().getId(),
                score.getProduct().getName(),
                category == null ? null : category.getName(),
                score.getPeriod(),
                score.getSceneType(),
                score.isPrimary(),
                score.getBonusSubtotal(),
                // 正值直接回，負號由 UI 加（AC-04-7）
                score.getPenaltySubtotal(),
                score.getFinalScore(),
                score.getGrade(),
                score.getConfidence(),
                score.isLowConfidence(),
                score.isRiskSuppressed(),
                bonusFactors,
                penaltyFactors);
    }

    /**
     * 試算結果的一列（§8.2 POST /scores/simulate、AC-04-4）。
     *
     * <p>與 {@link #toRankingRows} 的差別：那支的分數、分級全部從 entity 讀，
     * 這支從<b>參數</b>拿。試算不能寫入資料庫，所以不可以先 {@code score.setXxx(...)}
     * 再重用那支——managed entity 一被改，交易提交時 dirty checking 就把試算結果
     * 寫進共用資料庫了。用參數傳讓這個錯誤在編譯層面就寫不出來。
     *
     * <p>回應型別仍是 {@link ScoreRankingRowResponse}：試算的輸出就是一張排行榜，
     * 欄位與正式排行相同，前端可以用同一個表格元件渲染。
     *
     * @param effectiveWeights §5.7 分攤後的權重。長條顯示的是「這次試算實際用的權重」，
     *                         不是資料庫裡那組舊權重——否則畫面上的權重與分數對不起來
     */
    public static ScoreRankingRowResponse toSimulatedRow(
            ProductScore score,
            List<ScoreFactor> factors,
            Map<FactorCode, BigDecimal> effectiveWeights,
            BigDecimal bonusSubtotal,
            BigDecimal finalScore,
            Grade grade) {

        List<ScoreFactorBarResponse> bars = factors.stream()
                .filter(f -> !f.isPenalty())
                .sorted(Comparator.comparing(ScoreFactor::getFactorCode))
                .map(f -> new ScoreFactorBarResponse(
                        f.getFactorCode(),
                        f.getNormalizedValue(),
                        // 無資料的因子不在 effectiveWeights 裡，回 null（權重已被分攤掉）
                        effectiveWeights.get(f.getFactorCode()),
                        f.isDataAvailable(),
                        f.isImputed()))
                .toList();

        // 分數三欄用重算值；扣分不重算（§5.2.2），由 buildRankingRow 沿用原值
        return buildRankingRow(score, bars, bonusSubtotal, finalScore, grade);
    }

    /**
     * 歷史分數趨勢（§8.2 GET /products/{id}/scores/history）。
     *
     * <p>不帶因子明細，理由見 {@link ScoreHistoryPointResponse}。
     *
     * <p>順序沿用呼叫端傳進來的順序（目前是
     * {@code findByProductIdOrderByCalculatedAtDesc}，最新在前）。
     * 這裡不重排：排序是查詢的職責，Mapper 只做轉換。
     *
     * @param scores 該品項的歷史分數，<b>包含 {@code isActive = false} 的舊列</b>（§5.10）
     */
    public static List<ScoreHistoryPointResponse> toHistoryPoints(List<ProductScore> scores) {
        return scores.stream().map(ScoreMapper::toHistoryPoint).toList();
    }

    private static ScoreHistoryPointResponse toHistoryPoint(ProductScore score) {
        return new ScoreHistoryPointResponse(
                score.getId(),
                score.getPeriod(),
                score.getSceneType(),
                score.isPrimary(),
                score.isActive(),
                score.getBonusSubtotal(),
                // 正值直接回，負號由 UI 加（AC-04-7）
                score.getPenaltySubtotal(),
                score.getFinalScore(),
                score.getGrade(),
                score.getConfidence(),
                toOffset(score.getCalculatedAt()));
    }

    /**
     * entity 存 Instant（絕對時刻、無時區），API 回 OffsetDateTime（帶 +08:00）。
     * §8.1：回應時間一律以 +08:00 呈現。做法與 {@code WeightVersionMapper} 一致。
     */
    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : instant.atZone(API_ZONE).toOffsetDateTime();
    }

}
