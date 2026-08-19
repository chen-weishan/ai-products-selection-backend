package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.SocialPlatform;
import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import lombok.*;

/**
 * 人工熱度標記（規格書 §7.2 manual_heat_tag、FR-14-1）。
 *
 * <p>刻意<b>只儲存連結與評級、不擷取頁面內容</b>，避開重製與個資問題。
 * 採購人員本來就在瀏覽這些平台，把觀察結構化就能取得早期訊號，
 * 無法律風險、無維運成本。設計目標是 30 秒內填完。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "manual_heat_tag")
public class ManualHeatTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_url", nullable = false, length = 512)
    private String sourceUrl;

    /** 由 source_url 自動解析，判定錯誤時可手動覆寫（AC-14-1）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private SocialPlatform platform;

    /** 1–5，文字錨點：1 零星討論、5 洗版等級。 */
    @Column(name = "heat_level", nullable = false)
    private short heatLevel;

    /** A 軌品項；與 keyword 至少擇一。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    /** B 軌關鍵字；與 product 至少擇一。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "keyword_id")
    private TrendKeyword keyword;

    @Column(name = "observed_at", nullable = false)
    @Builder.Default
    private Instant observedAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tagged_by", nullable = false)
    private AppUser taggedBy;

    @Column(length = 255)
    private String note;

    /**
     * 時間衰減係數（AC-14-2）：14 天後 ×0.5、30 天後 ×0。
     * 人工觀察的時效比程式化來源短得多，不衰減會讓一個月前的洗版
     * 一直把分數撐在高點。
     */
    public double decayFactor(Instant now) {
        long days = Duration.between(observedAt, now).toDays();
        if (days >= 30) {
            return 0.0;
        }
        return days >= 14 ? 0.5 : 1.0;
    }
}
