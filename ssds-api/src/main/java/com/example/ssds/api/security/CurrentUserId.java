package com.example.ssds.api.security;

import com.example.ssds.util.UserDetailsImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 取得目前登入者的使用者 id。
 *
 * <p>實際掛上的 {@code JwtAuthFilter} 把 {@link UserDetailsImpl} 放進 principal，
 * 不是 {@link Long}；直接強轉 {@code (Long) getPrincipal()} 會丟出 ClassCastException 而回 500。
 * 集中在這裡處理，並同時相容 principal 直接是 {@link Long} 的情況。
 */
public final class CurrentUserId {

    private CurrentUserId() {}

    public static Long require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new IllegalStateException("目前沒有登入者");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserDetailsImpl user) {
            return user.getId();
        }
        if (principal instanceof Long id) {
            return id;
        }
        throw new IllegalStateException("無法從 principal 取得使用者 id：" + principal.getClass().getName());
    }
}