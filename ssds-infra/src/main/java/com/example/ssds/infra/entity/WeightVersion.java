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
 * <p>AC-08-2：狀態為 ACTIVE 的版本不可編輯，只能建立新版本。
 * 資料庫端以 partial unique index 保證全表最多一筆 ACTIVE。
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

    /** §5.6：門檻跟著版本走，各排行榜可獨立設定，不寫死在程式。 */
    @Column(name = "grade_a_threshold", nullable = false)
    @Builder.Default
    private int gradeAThreshold = 85;

    @Column(name = "grade_b_threshold", nullable = false)
    @Builder.Default
    private int gradeBThreshold = 70;

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
