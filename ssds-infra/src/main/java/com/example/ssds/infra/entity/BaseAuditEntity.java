package com.example.ssds.infra.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 帶 created_at / updated_at 的實體共用父類別。
 *
 * <p>用 Hibernate 的 {@code @CreationTimestamp} / {@code @UpdateTimestamp} 而非
 * Spring Data 的 {@code @CreatedDate}，理由是後者需要額外開 {@code @EnableJpaAuditing}
 * 且只在走 Repository 時生效；Hibernate 這組在 flush 當下由 ORM 直接填，
 * 不管是誰觸發的寫入都蓋得到。
 *
 * <p>資料庫端另有 {@code DEFAULT now()}，兩者刻意重複：JPA 走不到的路徑
 * （Flyway seed、手動 SQL 修補）也要有時間戳，欄位才能一律 NOT NULL。
 *
 * <p>時間一律用 {@link Instant}（UTC 瞬時），不用 {@code LocalDateTime}。
 * 規格書 §3.2 明訂資料庫存 UTC、應用層轉 Asia/Taipei，
 * 而 {@code LocalDateTime} 不帶時區，序列化到前端時會失去這個保證。
 */
@Getter
@Setter
@MappedSuperclass
public abstract class BaseAuditEntity {

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
