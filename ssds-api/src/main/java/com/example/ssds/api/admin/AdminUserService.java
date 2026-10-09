package com.example.ssds.api.admin;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.security.CurrentUserId;
import com.example.ssds.core.domain.RoleCode;
import com.example.ssds.core.domain.UserStatus;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.Role;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.RoleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-13 使用者管理（§2.1 權限列 23）。每次變更寫 audit_log（AC-13-1），稽核內容不含密碼雜湊。
 *
 * <p>防呆：管理員不能停用自己或拿掉自己的 SYS_ADMIN；系統至少保留一個啟用中的 SYS_ADMIN，
 * 否則再也沒有人能進 S-14 把帳號救回來。
 */
@Service
public class AdminUserService {
    static final int PASSWORD_MIN_LENGTH = 8;

    private final AppUserRepository users;
    private final RoleRepository roles;
    private final AuditLogRepository audits;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper mapper;

    public AdminUserService(
            AppUserRepository users,
            RoleRepository roles,
            AuditLogRepository audits,
            PasswordEncoder passwordEncoder,
            ObjectMapper mapper) {
        this.users = users;
        this.roles = roles;
        this.audits = audits;
        this.passwordEncoder = passwordEncoder;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return users.findAll().stream()
                .sorted(Comparator.comparing(AppUser::getId))
                .map(UserResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RoleOption> roleOptions() {
        return roles.findAll().stream()
                .sorted(Comparator.comparing(role -> role.getCode().ordinal()))
                .map(role -> new RoleOption(role.getCode(), role.getName(), role.getDescription()))
                .toList();
    }

    @Transactional
    public UserResponse create(CreateUserRequest request, String sourceIp) {
        String email = normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "此 Email 已有帳號：" + email);
        }
        validatePassword(request.password());
        AppUser user = AppUser.builder()
                .email(email)
                .displayName(requireDisplayName(request.displayName()))
                .passwordHash(passwordEncoder.encode(request.password()))
                .status(UserStatus.ACTIVE)
                .roles(resolveRoles(request.roles()))
                .build();
        AppUser saved = users.saveAndFlush(user);
        audit("CREATE_USER", saved.getId(), null, snapshot(saved), sourceIp);
        return UserResponse.from(saved);
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request, String sourceIp) {
        AppUser user = find(id);
        Map<String, Object> before = snapshot(user);
        Set<Role> newRoles = resolveRoles(request.roles());
        boolean keepsAdmin = newRoles.stream().anyMatch(role -> role.getCode() == RoleCode.SYS_ADMIN);
        if (!keepsAdmin && isActiveAdmin(user)) {
            if (user.getId().equals(CurrentUserId.require())) {
                throw invalidState("不能移除自己的系統管理員角色");
            }
            ensureAnotherActiveAdmin(user.getId());
        }
        user.setDisplayName(requireDisplayName(request.displayName()));
        user.setRoles(newRoles);
        AppUser saved = users.saveAndFlush(user);
        audit("UPDATE_USER", saved.getId(), before, snapshot(saved), sourceIp);
        return UserResponse.from(saved);
    }

    @Transactional
    public UserResponse changeStatus(Long id, UserStatus status, String sourceIp) {
        if (status == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "請指定帳號狀態");
        AppUser user = find(id);
        if (user.getStatus() == status) return UserResponse.from(user);
        Map<String, Object> before = snapshot(user);
        if (status == UserStatus.DISABLED) {
            if (user.getId().equals(CurrentUserId.require())) {
                throw invalidState("不能停用自己的帳號");
            }
            if (isActiveAdmin(user)) ensureAnotherActiveAdmin(user.getId());
        }
        user.setStatus(status);
        AppUser saved = users.saveAndFlush(user);
        audit(status == UserStatus.DISABLED ? "DISABLE_USER" : "ENABLE_USER", saved.getId(), before, snapshot(saved), sourceIp);
        return UserResponse.from(saved);
    }

    @Transactional
    public UserResponse resetPassword(Long id, String password, String sourceIp) {
        AppUser user = find(id);
        validatePassword(password);
        Map<String, Object> before = snapshot(user);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        AppUser saved = users.saveAndFlush(user);
        // 稽核只記「有重設」，不記任何密碼資訊
        Map<String, Object> after = snapshot(saved);
        after.put("passwordReset", true);
        audit("RESET_USER_PASSWORD", saved.getId(), before, after, sourceIp);
        return UserResponse.from(saved);
    }

    @Transactional
    public UserResponse unlock(Long id, String sourceIp) {
        AppUser user = find(id);
        if (!user.isLocked() && user.getFailedAttempts() == 0) return UserResponse.from(user);
        Map<String, Object> before = snapshot(user);
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        AppUser saved = users.saveAndFlush(user);
        audit("UNLOCK_USER", saved.getId(), before, snapshot(saved), sourceIp);
        return UserResponse.from(saved);
    }

    private AppUser find(Long id) {
        return users.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到使用者 #" + id));
    }

    private Set<Role> resolveRoles(List<RoleCode> codes) {
        if (codes == null || codes.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "至少要指派一個角色");
        }
        Set<Role> resolved = new LinkedHashSet<>();
        for (RoleCode code : new LinkedHashSet<>(codes)) {
            if (code == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "角色不可為空");
            resolved.add(roles.findByCode(code).orElseThrow(() ->
                    new BusinessException(ErrorCode.VALIDATION_FAILED, "系統中沒有角色：" + code)));
        }
        return resolved;
    }

    private static boolean isActiveAdmin(AppUser user) {
        return user.getStatus() == UserStatus.ACTIVE
                && user.getRoles().stream().anyMatch(role -> role.getCode() == RoleCode.SYS_ADMIN);
    }

    private void ensureAnotherActiveAdmin(Long excludedUserId) {
        boolean another = users.findByStatus(UserStatus.ACTIVE).stream()
                .anyMatch(other -> !other.getId().equals(excludedUserId) && isActiveAdmin(other));
        if (!another) throw invalidState("系統至少要保留一位啟用中的系統管理員");
    }

    private static String normalizeEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || normalized.length() > 150) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Email 格式不正確");
        }
        return normalized;
    }

    private static String requireDisplayName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty() || normalized.length() > 50) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "顯示名稱必填，且不可超過 50 字");
        }
        return normalized;
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < PASSWORD_MIN_LENGTH || password.length() > 72) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "密碼長度須為 " + PASSWORD_MIN_LENGTH + "～72 字元");
        }
        if (password.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "密碼不可全為空白");
        }
    }

    private static BusinessException invalidState(String message) {
        return new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, message);
    }

    private static Map<String, Object> snapshot(AppUser user) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("email", user.getEmail());
        value.put("displayName", user.getDisplayName());
        value.put("status", user.getStatus());
        value.put("roles", user.getRoles().stream().map(role -> role.getCode().name()).sorted().toList());
        // 注入的 Jackson 2 ObjectMapper 未註冊 JavaTimeModule，Instant 先轉字串
        value.put("lockedUntil", user.getLockedUntil() == null ? null : user.getLockedUntil().toString());
        return value;
    }

    private void audit(String action, Long userId, Object before, Object after, String sourceIp) {
        audits.save(AuditLog.builder()
                .user(users.getReferenceById(CurrentUserId.require()))
                .action(action)
                .entityType("AppUser")
                .entityId(userId)
                .beforeJson(before == null ? null : write(before))
                .afterJson(write(after))
                .ip(sourceIp)
                .build());
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("無法序列化稽核內容", exception);
        }
    }

    public record CreateUserRequest(String email, String displayName, String password, List<RoleCode> roles) {}

    public record UpdateUserRequest(String displayName, List<RoleCode> roles) {}

    public record StatusRequest(UserStatus status) {}

    public record PasswordRequest(String password) {}

    public record RoleOption(RoleCode code, String name, String description) {}

    public record UserResponse(
            Long id,
            String email,
            String displayName,
            UserStatus status,
            List<RoleCode> roles,
            boolean locked,
            Instant lockedUntil,
            int failedAttempts,
            Instant createdAt,
            Instant updatedAt) {

        static UserResponse from(AppUser user) {
            return new UserResponse(
                    user.getId(),
                    user.getEmail(),
                    user.getDisplayName(),
                    user.getStatus(),
                    user.getRoles().stream().map(Role::getCode).sorted().toList(),
                    user.isLocked(),
                    user.isLocked() ? user.getLockedUntil() : null,
                    user.getFailedAttempts(),
                    user.getCreatedAt(),
                    user.getUpdatedAt());
        }
    }
}
