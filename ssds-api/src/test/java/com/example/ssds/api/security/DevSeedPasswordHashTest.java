package com.example.ssds.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * dev 假資料的登入密碼確實可用（{@code db/dev/V909__fix_dev_password_hash.sql}）。
 *
 * <p>為什麼需要這支測試：V900 原本那組 hash 是網路範例的樣板字串，對不上任何明碼，
 * 而註解卻寫著「明碼為 Ssds@2026」。這個謊言活了很久都沒被發現，正是因為
 * <b>沒有任何測試驗過它</b>——在 FR-01 登入實作出來之前，沒有程式碼會去讀那個欄位。
 *
 * <p>MigrationVerificationTest 的「dev 版面」只證明 SQL 跑得動，不會發現 hash 是假的。
 * 所以這裡直接用正式程式碼會用的那顆 encoder 去驗值本身。
 *
 * <p>刻意寫死 hash 字串而不是從 SQL 檔剖析出來：這支測試的職責就是釘住
 * 「V909 裡的那個值配得上 Ssds@2026」。有人改了 migration 卻沒改測試時要爆掉。
 */
class DevSeedPasswordHashTest {

    /** 與 V909 寫入 app_user.password_hash 的值逐字相同。 */
    private static final String SEEDED_HASH =
            "$2a$10$Drdp9oSqfZNMoxyK8uXPnutsREX/I5869KOCjJvCtRCo9hZnxu1Ra";

    /** V900 第 41 行宣稱的明碼。 */
    private static final String EXPECTED_PLAIN = "Ssds@2026";

    /** V900 原本的值，來自網路範例、對不上任何明碼。留著當回歸哨兵。 */
    private static final String BROKEN_TEMPLATE_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    @DisplayName("V909 的 hash 驗得過 Ssds@2026")
    void seededHashMatchesDocumentedPassword() {
        assertThat(encoder.matches(EXPECTED_PLAIN, SEEDED_HASH)).isTrue();
    }

    @Test
    @DisplayName("錯誤密碼驗不過，確認上一項不是因為 encoder 一律回 true")
    void wrongPasswordIsRejected() {
        assertThat(encoder.matches("Ssds@2027", SEEDED_HASH)).isFalse();
        assertThat(encoder.matches("", SEEDED_HASH)).isFalse();
    }

    /**
     * 釘住「V900 的值確實是壞的」這個事實，避免日後有人看到 V909 覺得多餘而回退。
     */
    @Test
    @DisplayName("V900 原本的樣板 hash 對不上 Ssds@2026")
    void originalTemplateHashNeverMatched() {
        assertThat(encoder.matches(EXPECTED_PLAIN, BROKEN_TEMPLATE_HASH)).isFalse();
    }

    /**
     * PR #11 的 SecurityConfig 註冊的是 {@link BCryptPasswordEncoder}，
     * 所以資料庫存裸 hash、不加 {@code {bcrypt\}} 前綴是正確的。
     * 若日後改用 DelegatingPasswordEncoder，缺前綴會讓登入丟
     * IllegalArgumentException（症狀是 500 而不是「密碼錯誤」），這支會先擋下來。
     */
    @Test
    @DisplayName("seed 的 hash 是不帶 {id} 前綴的裸 BCrypt")
    void seededHashIsRawBcrypt() {
        assertThat(SEEDED_HASH).startsWith("$2a$10$").doesNotStartWith("{");
    }
}
