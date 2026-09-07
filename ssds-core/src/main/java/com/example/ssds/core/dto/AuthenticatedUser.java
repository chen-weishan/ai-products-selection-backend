package com.example.ssds.core.dto;

import java.util.List;

/** FR-01／§8.2 `/auth/me`：登入者基本資料與角色清單。 */
public record AuthenticatedUser(
        Long id,
        String email,
        String displayName,
        List<String> roles) {
}