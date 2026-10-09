package com.example.ssds.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ssds.core.domain.UserStatus;
import com.example.ssds.infra.entity.AppUser;
import org.junit.jupiter.api.Test;

/** FR-13 停用帳號：JwtAuthFilter 依 isEnabled 拒絕舊 token。 */
class UserDetailsImplTest {

    @Test
    void disabledUserIsNotEnabled() {
        AppUser user = AppUser.builder().id(7L).email("x@ssds.dev").displayName("x").passwordHash("h")
                .status(UserStatus.DISABLED).build();

        assertThat(UserDetailsImpl.build(user).isEnabled()).isFalse();
        user.setStatus(UserStatus.ACTIVE);
        assertThat(UserDetailsImpl.build(user).isEnabled()).isTrue();
    }
}
