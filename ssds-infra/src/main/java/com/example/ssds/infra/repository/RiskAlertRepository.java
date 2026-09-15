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
}
