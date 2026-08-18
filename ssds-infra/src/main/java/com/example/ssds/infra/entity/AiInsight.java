package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.InsightType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * AI 分析結果（規格書 §7.2 ai_insight）。
 *
 * <p>同一品項同一類型只有一筆 {@link #current} 為 true，其餘是歷史版本
 * （資料庫端以 partial unique index 保證）。不直接覆寫舊列的理由：
 * 決策紀錄會指向「當時的那一筆建議」，覆寫會讓事後檢討失去依據。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_insight")
public class AiInsight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Enumerated(EnumType.STRING)
    @Column(name = "insight_type", nullable = false, length = 24)
    private InsightType insightType;

    /** 結構化結果。§3.2：輸出強制 JSON 並以 JSON Schema 驗證後才寫入。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_json", nullable = false, columnDefinition = "jsonb")
    private String contentJson;

    @Column(length = 80)
    private String model;

    /** Prompt 模板版本，換模板後可比對輸出品質差異（§6.4）。 */
    @Column(name = "prompt_version", length = 20)
    private String promptVersion;

    /** 本次分析引用的評論筆數，供 §6.6 幻覺防護的可追溯性揭露。 */
    @Column(name = "source_review_count", nullable = false)
    @Builder.Default
    private int sourceReviewCount = 0;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    /** §3.2 模型策略為一律使用免費模型，故正常為 0；保留欄位以便換模型後計費。 */
    @Column(name = "cost_usd", nullable = false, precision = 8, scale = 5)
    @Builder.Default
    private BigDecimal costUsd = BigDecimal.ZERO;

    @Column(name = "generated_at", nullable = false)
    @Builder.Default
    private Instant generatedAt = Instant.now();

    @Column(name = "is_current", nullable = false)
    @Builder.Default
    private boolean current = true;
}
