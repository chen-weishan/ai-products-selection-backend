package com.example.ssds.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssds.api.admin.RuntimeSettingsService.OperationalConfig;
import com.example.ssds.api.common.response.ApiError;
import com.example.ssds.core.domain.UserStatus;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.repository.AppUserRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceRuntimeConfigTest {

    @Test
    void runtimeThresholdControlsFailedLoginLockout() {
        AppUserRepository users = mock(AppUserRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        AuthService service = new AuthService();
        ReflectionTestUtils.setField(service, "userRepository", users);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwords);
        AppUser user = AppUser.builder()
                .id(7L)
                .email("buyer@example.com")
                .passwordHash("hash")
                .displayName("Buyer")
                .status(UserStatus.ACTIVE)
                .failedAttempts(1)
                .build();
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwords.matches("wrong", "hash")).thenReturn(false);
        service.reconfigure(new OperationalConfig(
                2, 7, 14, 30, 10,
                new BigDecimal("0.5"), new BigDecimal("0.7"), 200));
        Instant before = Instant.now();

        var response = service.authenticate(Map.of("email", user.getEmail(), "password", "wrong"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(((ApiError) response.getBody()).code()).isEqualTo("AUTH_LOCKED");
        assertThat(user.getFailedAttempts()).isZero();
        assertThat(Duration.between(before, user.getLockedUntil()).toMinutes()).isBetween(6L, 7L);
    }
}
