package com.example.ssds.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.admin.AdminUserService.CreateUserRequest;
import com.example.ssds.api.admin.AdminUserService.UpdateUserRequest;
import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.core.domain.RoleCode;
import com.example.ssds.core.domain.UserStatus;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.Role;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.RoleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** FR-13 使用者管理：防呆規則與 AC-13-1 稽核（不含密碼）。 */
class AdminUserServiceTest {
    private static final long ADMIN_ID = 4L;

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final AuditLogRepository audits = mock(AuditLogRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AdminUserService service = new AdminUserService(users, roles, audits, encoder, new ObjectMapper());

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ADMIN_ID, null, List.of()));
        for (RoleCode code : RoleCode.values()) {
            when(roles.findByCode(code)).thenReturn(Optional.of(role(code)));
        }
        when(encoder.encode(any())).thenAnswer(invocation -> "hash:" + invocation.getArgument(0));
        when(users.saveAndFlush(any())).thenAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            if (user.getId() == null) user.setId(99L);
            return user;
        });
        when(users.getReferenceById(any())).thenAnswer(invocation -> AppUser.builder().id(invocation.getArgument(0)).build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static Role role(RoleCode code) {
        return Role.builder().id((long) code.ordinal() + 1).code(code).name(code.name()).build();
    }

    private static AppUser user(long id, UserStatus status, RoleCode... codes) {
        Set<Role> assigned = new LinkedHashSet<>();
        for (RoleCode code : codes) assigned.add(role(code));
        return AppUser.builder().id(id).email("u" + id + "@ssds.dev").displayName("U" + id)
                .passwordHash("old").status(status).roles(assigned).build();
    }

    private AuditLog lastAudit() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(audits).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("新增帳號：Email 轉小寫、密碼雜湊、稽核不含密碼")
    void createsUserWithAudit() {
        when(users.existsByEmail("new@ssds.dev")).thenReturn(false);

        var created = service.create(new CreateUserRequest(" New@SSDS.dev ", " 新人 ", "Secret123",
                List.of(RoleCode.BUYER, RoleCode.BUYER)), "127.0.0.1");

        assertThat(created.email()).isEqualTo("new@ssds.dev");
        assertThat(created.displayName()).isEqualTo("新人");
        assertThat(created.roles()).containsExactly(RoleCode.BUYER);
        AuditLog audit = lastAudit();
        assertThat(audit.getAction()).isEqualTo("CREATE_USER");
        assertThat(audit.getEntityId()).isEqualTo(99L);
        assertThat(audit.getAfterJson()).contains("new@ssds.dev").doesNotContain("Secret123").doesNotContain("hash:");
        assertThat(audit.getIp()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("Email 重複回 DUPLICATE_RESOURCE")
    void duplicateEmail() {
        when(users.existsByEmail("buyer@ssds.dev")).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new CreateUserRequest("buyer@ssds.dev", "x", "Secret123", List.of(RoleCode.BUYER)), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    }

    @Test
    @DisplayName("密碼過短、未指派角色都擋下")
    void validatesPasswordAndRoles() {
        assertThatThrownBy(() -> service.create(
                new CreateUserRequest("a@ssds.dev", "a", "short", List.of(RoleCode.BUYER)), null))
                .hasMessageContaining("密碼長度");
        assertThatThrownBy(() -> service.create(
                new CreateUserRequest("a@ssds.dev", "a", "Secret123", List.of()), null))
                .hasMessageContaining("至少要指派一個角色");
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("不能停用自己")
    void cannotDisableSelf() {
        when(users.findById(ADMIN_ID)).thenReturn(Optional.of(user(ADMIN_ID, UserStatus.ACTIVE, RoleCode.SYS_ADMIN)));

        assertThatThrownBy(() -> service.changeStatus(ADMIN_ID, UserStatus.DISABLED, null))
                .hasMessageContaining("不能停用自己");
    }

    @Test
    @DisplayName("停用最後一位啟用中的系統管理員被擋")
    void keepsLastActiveAdmin() {
        AppUser otherAdmin = user(8L, UserStatus.ACTIVE, RoleCode.SYS_ADMIN);
        when(users.findById(8L)).thenReturn(Optional.of(otherAdmin));
        when(users.findByStatus(UserStatus.ACTIVE)).thenReturn(List.of(otherAdmin, user(1L, UserStatus.ACTIVE, RoleCode.BUYER)));

        assertThatThrownBy(() -> service.changeStatus(8L, UserStatus.DISABLED, null))
                .hasMessageContaining("至少要保留一位");
    }

    @Test
    @DisplayName("還有其他管理員時可停用，稽核記 DISABLE_USER")
    void disablesWhenAnotherAdminExists() {
        AppUser target = user(8L, UserStatus.ACTIVE, RoleCode.SYS_ADMIN);
        when(users.findById(8L)).thenReturn(Optional.of(target));
        when(users.findByStatus(UserStatus.ACTIVE)).thenReturn(List.of(target, user(ADMIN_ID, UserStatus.ACTIVE, RoleCode.SYS_ADMIN)));

        var result = service.changeStatus(8L, UserStatus.DISABLED, null);

        assertThat(result.status()).isEqualTo(UserStatus.DISABLED);
        AuditLog audit = lastAudit();
        assertThat(audit.getAction()).isEqualTo("DISABLE_USER");
        assertThat(audit.getBeforeJson()).contains("ACTIVE");
        assertThat(audit.getAfterJson()).contains("DISABLED");
    }

    @Test
    @DisplayName("不能拿掉自己的 SYS_ADMIN 角色")
    void cannotDropOwnAdminRole() {
        when(users.findById(ADMIN_ID)).thenReturn(Optional.of(user(ADMIN_ID, UserStatus.ACTIVE, RoleCode.SYS_ADMIN)));

        assertThatThrownBy(() -> service.update(ADMIN_ID, new UpdateUserRequest("Admin", List.of(RoleCode.VIEWER)), null))
                .hasMessageContaining("不能移除自己的系統管理員角色");
    }

    @Test
    @DisplayName("改角色與名稱，稽核含前後角色")
    void updatesRoles() {
        when(users.findById(1L)).thenReturn(Optional.of(user(1L, UserStatus.ACTIVE, RoleCode.BUYER)));

        var result = service.update(1L, new UpdateUserRequest("採購小明", List.of(RoleCode.BUYER_LEAD, RoleCode.BUYER)), null);

        assertThat(result.roles()).containsExactly(RoleCode.BUYER, RoleCode.BUYER_LEAD);
        AuditLog audit = lastAudit();
        assertThat(audit.getAction()).isEqualTo("UPDATE_USER");
        assertThat(audit.getBeforeJson()).doesNotContain("BUYER_LEAD");
        assertThat(audit.getAfterJson()).contains("BUYER_LEAD").contains("採購小明");
    }

    @Test
    @DisplayName("重設密碼同時解鎖，稽核只記有重設")
    void resetPasswordUnlocks() {
        AppUser locked = user(1L, UserStatus.ACTIVE, RoleCode.BUYER);
        locked.setFailedAttempts(3);
        locked.setLockedUntil(Instant.now().plusSeconds(600));
        when(users.findById(1L)).thenReturn(Optional.of(locked));

        var result = service.resetPassword(1L, "NewSecret1", null);

        assertThat(result.locked()).isFalse();
        assertThat(locked.getPasswordHash()).isEqualTo("hash:NewSecret1");
        assertThat(locked.getFailedAttempts()).isZero();
        AuditLog audit = lastAudit();
        assertThat(audit.getAction()).isEqualTo("RESET_USER_PASSWORD");
        assertThat(audit.getAfterJson()).contains("passwordReset").doesNotContain("NewSecret1");
    }

    @Test
    @DisplayName("解鎖：清除鎖定時間與失敗次數")
    void unlocks() {
        AppUser locked = user(1L, UserStatus.ACTIVE, RoleCode.BUYER);
        locked.setFailedAttempts(5);
        locked.setLockedUntil(Instant.now().plusSeconds(600));
        when(users.findById(1L)).thenReturn(Optional.of(locked));

        var before = AdminUserService.UserResponse.from(locked);
        var result = service.unlock(1L, null);

        assertThat(before.locked()).isTrue();
        assertThat(result.locked()).isFalse();
        assertThat(lastAudit().getAction()).isEqualTo("UNLOCK_USER");
    }
}
