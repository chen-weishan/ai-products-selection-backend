package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.UserStatus;
import com.example.ssds.infra.entity.AppUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** 使用者查詢（規格書 §7.2 app_user）。 */
@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** 登入用。roles 已是 EAGER，不需額外 EntityGraph。 */
    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    List<AppUser> findByStatus(UserStatus status);

    /**
     * 登入失敗計數 +1。用 UPDATE 而非「讀出來改再存回去」，
     * 避免兩個並行的失敗嘗試互相覆蓋計數（lost update）。
     */
    @Modifying
    @Query("update AppUser u set u.failedAttempts = u.failedAttempts + 1 where u.id = :id")
    int incrementFailedAttempts(@Param("id") Long id);

    @Modifying
    @Query("update AppUser u set u.failedAttempts = 0, u.lockedUntil = null where u.id = :id")
    int resetFailedAttempts(@Param("id") Long id);
}
