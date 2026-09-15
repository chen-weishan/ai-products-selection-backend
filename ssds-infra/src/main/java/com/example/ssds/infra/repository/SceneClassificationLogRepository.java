package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.SceneClassificationLog;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** 情境判定紀錄（規格書 §7.2 scene_classification_log）。 */
@Repository
public interface SceneClassificationLogRepository
        extends JpaRepository<SceneClassificationLog, Long> {

    Optional<SceneClassificationLog> findFirstByProductIdOrderByCreatedAtDesc(Long productId);

    List<SceneClassificationLog> findByProductIdOrderByCreatedAtDesc(Long productId);

    /**
     * FR-02 排行的人工覆寫標記：批次取每個品項「最新一筆」判定紀錄。
     * 覆寫與否由服務層檢查（{@code overriddenBy} 非空且情境相符），
     * 因此這裡不過濾覆寫 —— 若只查覆寫列，會把「曾覆寫但後來重新 AI 判定」的品項誤標。
     */
    @Query("""
            select l from SceneClassificationLog l
            where l.product.id in :productIds
              and l.createdAt = (
                  select max(l2.createdAt) from SceneClassificationLog l2
                  where l2.product.id = l.product.id)
            """)
    List<SceneClassificationLog> findLatestByProductIds(
            @Param("productIds") Collection<Long> productIds);

    /**
     * FR-04 排行列的「經人工覆寫」標記：一次撈一整頁品項在該 period 的判定紀錄，
     * 避免逐列查詢造成 N+1。
     *
     * <p><b>與 {@link #findLatestByProductIds} 的分工</b>：那支取的是「全域最新一筆」，
     * 適合 FR-02 儀表板（只看當期）；這支多篩一個 {@code period}。
     * FR-04 排行可以查歷史期間，此時兩者會得到不同答案——某品項 7 月被覆寫、
     * 8 月重新判定未覆寫時，查 7 月的排行必須顯示「已覆寫」，用全域最新會答錯。
     *
     * <p>回的是<b>全部</b>紀錄而不是只回覆寫過的——同一品項同一 period 可能
     * 先判定、後覆寫、再重判，「現在是不是覆寫狀態」取決於<b>最新那筆</b>。
     * 只篩 {@code overriddenBy is not null} 會把「覆寫後又重跑判定」誤標成覆寫。
     * 由呼叫端依 createdAt 取每個品項的第一筆再判斷。
     */
    List<SceneClassificationLog> findByPeriodAndProductIdInOrderByCreatedAtDesc(
            String period, Collection<Long> productIds);

    /** FR-11-3 情境判定覆寫率：分子。 */
    long countByOverriddenByIsNotNullAndCreatedAtBetween(Instant from, Instant to);

    /** 分母。 */
    long countByCreatedAtBetween(Instant from, Instant to);
}
