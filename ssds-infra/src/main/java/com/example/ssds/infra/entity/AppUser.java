package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 系統使用者（規格書 §7.2 app_user）。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "app_user")
public class AppUser extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    /** BCrypt 雜湊。永不儲存明碼，也不對外序列化。 */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    /**
     * 一律用 {@code EnumType.STRING}。ORDINAL 會把列舉的宣告順序寫進資料庫，
     * 日後在中間插入一個值就會讓所有既有資料的語意整組平移。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    /** 連續登入失敗次數，成功登入時歸零。 */
    @Column(name = "failed_attempts", nullable = false)
    @Builder.Default
    private int failedAttempts = 0;

    /** 鎖定至此時刻；NULL 表未鎖定。 */
    @Column(name = "locked_until")
    private Instant lockedUntil;

    /**
     * 角色。以 {@code @ManyToMany} 直接對應 user_role join table，
     * 不另建 UserRole 實體 —— 該表只有兩個外鍵、沒有自己的屬性，
     * 拆成實體只會讓每次查角色都多一層轉換。
     *
     * <p>EAGER 是刻意的：授權判斷幾乎必然要用到角色，
     * 若設 LAZY，Security filter 在 session 外讀取會直接炸掉
     * （本專案已關閉 open-in-view）。
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    @Builder.Default
    private Set<Role> roles = new LinkedHashSet<>();

    /** 帳號目前是否可登入：狀態為 ACTIVE 且不在鎖定期間內。 */
    public boolean isLoginAllowed() {
        return status == UserStatus.ACTIVE
                && (lockedUntil == null || lockedUntil.isBefore(Instant.now()));
    }
}
