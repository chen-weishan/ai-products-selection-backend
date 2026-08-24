package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.WeightVersionStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 權重版本（規格書 §7.2 weight_version、FR-08）。
 *
 * <p>v1.0 的 weights_json 已由 {@link WeightProfile} 逐列取代：v2.0 一個版本
 * 帶四組情境權重，攤成列才能加索引、才能逐項比對兩個版本的差異（AC-15-4 回測）。
 *
 * <p>AC-08-2：狀態為 APPROVED 的版本不可編輯，只能建立新版本。
 *
 * <p>「已核准」與「現在生效中」是兩件事（v3.0 §7.2.5）：前者是 status，
 * 後者是 {@link #isCurrent} 旗標，資料庫端以 partial unique index 保證
 * 全表最多一筆 is_current。分級門檻不在本實體上——v3.0 已移除兩個純量
 * 門檻欄，改由 grade_threshold 逐榜保存，讀取一律走那張表。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "weight_version")
public class WeightVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** v1 / v2 / v3。 */
    @Column(name = "version_no", nullable = false, unique = true, length = 16)
    private String versionNo;

    /** 如「2026 夏季｜重毛利」。 */
    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private WeightVersionStatus status = WeightVersionStatus.DRAFT;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    /**
     * 同一時間僅一筆為 true；{@code GET /weight-versions/active} 依此查詢（§7.2.5）。
     *
     * <p>不要用 {@code status == APPROVED} 當作「生效中」：核准後可以先不生效，
     * 而且歷史上被取代的版本也仍是 APPROVED。
     */
    @Column(name = "is_current", nullable = false)
    @Builder.Default
    private boolean isCurrent = false;

    /** 若本版由 §FR-15 季度校準產生，指向該次的 calibration_report。 */
    @Column(name = "source_calibration_id")
    private Long sourceCalibrationId;

    /**
     * 類別專屬權重覆寫。以 jsonb 儲存並映射為 String：
     * 這份內容只會整包讀寫、不做欄位級查詢，拆成實體反而多此一舉。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "category_override_json", columnDefinition = "jsonb")
    private String categoryOverrideJson;

    @Column(name = "change_note", length = 512)
    private String changeNote;

    /** AC-08-3：僅 BUYER_LEAD 可核准生效。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private AppUser approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private AppUser createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "version", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<WeightProfile> profiles = new ArrayList<>();

    public boolean isEditable() {
        return status == WeightVersionStatus.DRAFT;
    }
}
