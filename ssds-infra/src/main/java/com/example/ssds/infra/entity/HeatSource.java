package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.AdapterType;
import com.example.ssds.core.domain.HeatSourceCode;
import com.example.ssds.core.domain.SourceAvailability;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.*;

/**
 * 熱度來源註冊表（規格書 §7.2 heat_source、FR-14-2）。
 *
 * <p>Facebook／TikTok／小紅書不列入：三者均無合法的程式化資料管道
 * （附錄 C 法律評估），改由 MANUAL 人工標記涵蓋。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "heat_source")
public class HeatSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_code", nullable = false, unique = true, length = 32)
    private HeatSourceCode sourceCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "adapter_type", nullable = false, length = 32)
    private AdapterType adapterType;

    /** §5.3.2 合成權重。 */
    @Column(name = "composite_weight", nullable = false, precision = 4, scale = 3)
    @Builder.Default
    private BigDecimal compositeWeight = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private SourceAvailability availability = SourceAvailability.AVAILABLE;

    @Column(name = "quota_used", nullable = false)
    @Builder.Default
    private int quotaUsed = 0;

    /** null 表無額度上限（如人工標記）。 */
    @Column(name = "quota_limit")
    private Integer quotaLimit;

    @Column(name = "last_fetched_at")
    private Instant lastFetchedAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /**
     * 本次評分是否應計入此來源。
     * §5.3.2：不可用者權重視為 0，其餘來源按比例重新正規化，
     * 分數照常產生並於 UI 標示缺漏，不阻斷評分流程。
     */
    public boolean contributesToComposite() {
        return enabled && availability != SourceAvailability.UNAVAILABLE;
    }
}
