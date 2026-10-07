package com.example.ssds.api.calibration;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.core.domain.RoleCode;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 校準模組的操作者驗證與稽核。
 *
 * <p>第一道把關是 controller 的 {@code @PreAuthorize}（看 JWT 內的角色）；這裡以資料庫中的角色再驗一次，
 * 擋下 token 簽發後才被撤銷角色的使用者，並取得寫入 {@code reviewed_by}／稽核所需的 {@link AppUser}。
 * 與 {@code ProductCommandService} 在 service 層自行擋角色的做法一致。
 */
@Component
@RequiredArgsConstructor
class CalibrationActors {

    private final AppUserRepository appUserRepository;
    private final AuditLogRepository auditLogRepository;

    /**
     * @param actorEmail JWT subject；未帶 token 時為 null
     * @param allowed §2.1 權限矩陣允許的角色
     */
    AppUser require(String actorEmail, Set<RoleCode> allowed) {
        if (actorEmail == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        AppUser actor = appUserRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "登入使用者不存在或已失效"));
        boolean permitted = actor.getRoles().stream().anyMatch(role -> allowed.contains(role.getCode()));
        if (!permitted) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "此操作僅限 " + allowed + " 角色");
        }
        return actor;
    }

    void audit(AppUser actor, String action, String entityType, Long entityId,
            String before, String after, String ip) {
        auditLogRepository.save(AuditLog.builder()
                .user(actor)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .beforeJson(before)
                .afterJson(after)
                .ip(ip)
                .build());
    }
}
