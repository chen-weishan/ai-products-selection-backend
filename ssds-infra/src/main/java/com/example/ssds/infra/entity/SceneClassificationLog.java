package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.SceneType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 情境判定與人工覆寫紀錄（規格書 §7.2 scene_classification_log）。
 *
 * <p>權重校準的關鍵訊號來源：覆寫率過高代表判定規則需調整（FR-11-3）。
 *
 * <p>§5.4 約束：AI 必須輸出判定理由與依據訊號，否則判定無法稽核；
 * 信心值低於 0.5 或 Schema 驗證失敗時 {@link #aiSceneType} 為 null，
 * {@link #finalSceneType} 退回 REPLENISHMENT。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "scene_classification_log")
public class SceneClassificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_scene_type", length = 24)
    private SceneType aiSceneType;

    /** 0–1。低於 0.7 時信心度扣 10 分（§5.9）。 */
    @Column(name = "ai_confidence", precision = 3, scale = 2)
    private BigDecimal aiConfidence;

    @Column(name = "ai_reasoning", columnDefinition = "text")
    private String aiReasoning;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_alternative_scene", length = 24)
    private SceneType alternativeSceneType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_signals", columnDefinition = "jsonb")
    private List<String> signals;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_scene_type", nullable = false, length = 24)
    private SceneType finalSceneType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "overridden_by")
    private AppUser overriddenBy;

    /** 覆寫時必填（資料庫端亦有 CHECK 約束）。 */
    @Column(name = "override_reason", length = 255)
    private String overrideReason;

    @Column(name = "fallback_applied", nullable = false)
    @Builder.Default
    private boolean fallbackApplied = false;

    @Column(name = "fallback_reason", length = 32)
    private String fallbackReason;

    @Column(length = 80)
    private String model;

    @Column(name = "prompt_version", length = 20)
    private String promptVersion;

    @Column(name = "heat_bucket", nullable = false, length = 16)
    private String heatBucket;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 7)
    private String period;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public boolean isOverridden() {
        return overriddenBy != null;
    }
}
