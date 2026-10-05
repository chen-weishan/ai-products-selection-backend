package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.AlertStatus;
import com.example.ssds.core.domain.Severity;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.infra.entity.RiskAlert;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * 風險示警（規格書 §7.2 risk_alert、FR-10）。
 *
 * <p>
 * AC-10-2：預設清單排除 IGNORED，但可用篩選查回來 ——
 * 所以「預設查詢」與「全部查詢」是兩支方法，不是一支加旗標。
 */
@Repository
public interface RiskAlertRepository extends JpaRepository<RiskAlert, Long> {

    /** 預設清單：未忽略者。 */
    @EntityGraph(attributePaths = { "product", "product.category" })
    Page<RiskAlert> findByStatusNot(AlertStatus status, Pageable pageable);

    @EntityGraph(attributePaths = { "product", "product.category" })
    Page<RiskAlert> findByStatusAndSeverity(AlertStatus status, Severity severity, Pageable pageable);

     /** 篩選示警；未指定狀態時排除 IGNORED，並固定依嚴重度、偵測時間排序。 */
    @EntityGraph(attributePaths = { "product", "product.category" })
    @Query(value = """
            select r from RiskAlert r
            where ((:status is not null and r.status = :status)
               or (:status is null and r.status <> com.example.ssds.core.domain.AlertStatus.IGNORED))
            order by case r.severity
                         when com.example.ssds.core.domain.Severity.HIGH then 0
                         when com.example.ssds.core.domain.Severity.MEDIUM then 1
                         else 2 end,
                     r.detectedAt desc
            """,
            countQuery = """
            select count(r) from RiskAlert r
            where ((:status is not null and r.status = :status)
               or (:status is null and r.status <> com.example.ssds.core.domain.AlertStatus.IGNORED))
            """)
    Page<RiskAlert> findVisible(@Param("status") AlertStatus status, Pageable pageable);

    @EntityGraph(attributePaths = { "product", "product.category" })
    @Query(value = """
            select r from RiskAlert r
            where ((:status is null and r.status <> com.example.ssds.core.domain.AlertStatus.IGNORED)
                 or (:status is not null and r.status = :status))
              and (:severity is null or r.severity = :severity)
              and (:riskType is null or r.riskType = :riskType)
              and (:categoryId is null or r.product.category.id = :categoryId)
            order by case r.severity
                         when com.example.ssds.core.domain.Severity.HIGH then 0
                         when com.example.ssds.core.domain.Severity.MEDIUM then 1
                         else 2 end,
                     r.detectedAt desc
            """,
            countQuery = """
            select count(r) from RiskAlert r
            where ((:status is null and r.status <> com.example.ssds.core.domain.AlertStatus.IGNORED)
                 or (:status is not null and r.status = :status))
              and (:severity is null or r.severity = :severity)
              and (:riskType is null or r.riskType = :riskType)
              and (:categoryId is null or r.product.category.id = :categoryId)
            """)
    Page<RiskAlert> search(
            @Param("status") AlertStatus status,
            @Param("severity") Severity severity,
            @Param("riskType") String riskType,
            @Param("categoryId") Long categoryId,
            Pageable pageable);

    List<RiskAlert> findByProductIdOrderByDetectedAtDesc(Long productId);

    /** 儀表板的高風險計數（排除 IGNORED）。 */
    @Query("""
            SELECT COUNT(r) FROM RiskAlert r
            WHERE r.status <> com.example.ssds.core.domain.AlertStatus.IGNORED
              AND r.severity = :severity
              AND r.product.trackType = :trackType
              AND r.product.deletedAt IS NULL
            """)
    long countActiveBySeverityAndTrackType(
            @Param("severity") Severity severity, @Param("trackType") TrackType trackType);

    boolean existsByProductIdAndRiskTypeAndStatus(
            Long productId, String riskType, AlertStatus status);

    /** FR-02 KPI：未處理風險數量（不限嚴重度） */
    long countByStatus(AlertStatus status);

    /**
     * FR-02 儀表板排行風險指示：取得多個品項的最高嚴重度風險。
     * 回傳 Map<productId, severity>，僅包含 status = OPEN 的風險。
     */
    @Query("""
            SELECT r.product.id,
                   MIN(CASE r.severity
                           WHEN com.example.ssds.core.domain.Severity.HIGH   THEN 1
                           WHEN com.example.ssds.core.domain.Severity.MEDIUM THEN 2
                           ELSE 3 END)
            FROM RiskAlert r
            WHERE r.product.id IN :productIds AND r.status = com.example.ssds.core.domain.AlertStatus.OPEN
            GROUP BY r.product.id
            """)
    List<Object[]> findTopSeverityRankByProductIds(@Param("productIds") List<Long> productIds);

    /**
     * FR-10-2 去重：同一品項同一 {@code riskType} 於 7 日內已有 {@code OPEN} 的示警時，
     * 不重複開立，改更新那一筆的 {@code trigger_value} 與 {@code detected_at}。
     *
     * <p>七日的起算點由呼叫端算好後傳 {@code since}，不寫死在查詢裡——
     * 「現在」取決於那一次批次的執行時刻，讓查詢自己取 now 會使同一批次的
     * 前後品項用到不同的時間窗。
     *
     * <p>走 {@code idx_alert_dedup(product_id, risk_type, status, detected_at DESC)}。
     */
    Optional<RiskAlert> findFirstByProductIdAndRiskTypeAndStatusAndDetectedAtAfterOrderByDetectedAtDesc(
            Long productId, String riskType, AlertStatus status, Instant since);
            
    /**
     * 去重窗：OPEN 以 detected_at 起算；ACKNOWLEDGED／IGNORED 以 handled_at 起算，
     * 舊資料 handled_at 為空時回退 detected_at。OPEN 優先，避免較新的已處理列遮住仍有效的 OPEN。
     */
    @Query("""
            select r from RiskAlert r
            where r.product.id = :productId
              and r.riskType = :riskType
              and ((r.status = com.example.ssds.core.domain.AlertStatus.OPEN and r.detectedAt > :since)
                or (r.status <> com.example.ssds.core.domain.AlertStatus.OPEN
                    and coalesce(r.handledAt, r.detectedAt) > :since))
            order by case when r.status = com.example.ssds.core.domain.AlertStatus.OPEN then 0 else 1 end,
                     case when r.status = com.example.ssds.core.domain.AlertStatus.OPEN then r.detectedAt else coalesce(r.handledAt, r.detectedAt) end desc
            """)
    List<RiskAlert> findWithinDedupWindow(
            @Param("productId") Long productId,
            @Param("riskType") String riskType,
            @Param("since") Instant since,
            Pageable pageable);
}
