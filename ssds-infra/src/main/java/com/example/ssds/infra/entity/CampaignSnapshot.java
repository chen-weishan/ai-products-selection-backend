package com.example.ssds.infra.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 開團當下的環境快照（規格書 §7.2.8 campaign_snapshot）。
 *
 * <p><b>只存無法從 product_score + score_factor 還原的資訊。</b>
 * v2.0 把分數、分級、加減分小計、因子值全部再存一份 JSON，與那兩張表完全重複——
 * 而 §5.10 已保證評分永不覆寫、{@code decision_record.score_id} 也已綁定，
 * 那些值 join 回去就有，重複保存只是多一份會不同步的副本
 * （V904／V905 各自為了修正它而寫過一段同步邏輯，正是這個問題的症狀）。
 *
 * <p>留下來的三份 JSON 才是真的還原不了的：決策當下各來源的可用狀態、
 * 當下實際採用的合成權重、當下該榜的 A／B 門檻。三者都會隨設定改變而失去歷史值。
 *
 * <p>要拿分數與因子明細，走 {@code decision.getScore()}。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "campaign_snapshot")
public class CampaignSnapshot {

    /** 主鍵即 decision_id（1:1，§7.2.8）。 */
    @Id
    @Column(name = "decision_id")
    private Long decisionId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "decision_id")
    private DecisionRecord decision;

    /** 當時各熱度來源狀態，用於事後解釋「這筆分數少了哪些來源」。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_availability", columnDefinition = "jsonb")
    private String sourceAvailability;

    /**
     * 當下實際採用的各來源合成權重。
     *
     * <p>來源降級時權重會被重新正規化（§5.3.2），不記下來就無法解釋當天的熱度值。
     * V17 之前建立的列為 null，表示當時未記錄——不要用現行設定回填，
     * 那是拿「現在」冒充「當時」。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applied_composite_weights", columnDefinition = "jsonb")
    private String appliedCompositeWeights;

    /** 當下該榜的 A／B 門檻。門檻隨 weight_version 版本化，但版本可被退役。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applied_thresholds", columnDefinition = "jsonb")
    private String appliedThresholds;

    /** true 表情境被人工覆寫；FR-11-3 的覆寫率指標由此統計。 */
    @Column(name = "scene_overridden", nullable = false)
    @Builder.Default
    private boolean sceneOverridden = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
