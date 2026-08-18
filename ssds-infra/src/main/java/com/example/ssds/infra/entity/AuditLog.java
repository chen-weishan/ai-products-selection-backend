package com.example.ssds.infra.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 稽核紀錄（規格書 §7.2 audit_log）。BUYER_LEAD 與 SYS_ADMIN 可檢視（§2.1）。
 *
 * <p>不對 entity_id 建外鍵：本表要記錄的是「任何實體的任何變更」，
 * 包含已被刪除的目標；加外鍵會讓刪除操作連稽核紀錄一起帶走，
 * 那正好是最需要留下紀錄的時候。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 系統排程觸發的變更為 null。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 64)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", columnDefinition = "jsonb")
    private String beforeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", columnDefinition = "jsonb")
    private String afterJson;

    /** 長度 45 以容納 IPv6（含 IPv4-mapped 形式）。 */
    @Column(length = 45)
    private String ip;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
