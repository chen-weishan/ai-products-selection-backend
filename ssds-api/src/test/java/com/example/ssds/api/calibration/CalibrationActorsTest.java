package com.example.ssds.api.calibration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.core.domain.RoleCode;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.Role;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** AC-15-3：方法層安全未啟用，角色必須在 service 層擋下（§2.1 權限列 17 僅 BUYER_LEAD）。 */
@ExtendWith(MockitoExtension.class)
class CalibrationActorsTest {

    @Mock private AppUserRepository appUserRepository;
    @Mock private AuditLogRepository auditLogRepository;

    private CalibrationActors actors;

    @BeforeEach
    void setUp() {
        actors = new CalibrationActors(appUserRepository, auditLogRepository);
    }

    @Test
    void buyerLeadPasses() {
        AppUser lead = user("lead@ssds.dev", RoleCode.BUYER_LEAD);
        when(appUserRepository.findByEmail("lead@ssds.dev")).thenReturn(Optional.of(lead));

        assertThat(actors.require("lead@ssds.dev", EnumSet.of(RoleCode.BUYER_LEAD))).isSameAs(lead);
    }

    @Test
    void otherRolesAreForbiddenEvenSysAdmin() {
        when(appUserRepository.findByEmail("admin@ssds.dev"))
                .thenReturn(Optional.of(user("admin@ssds.dev", RoleCode.SYS_ADMIN)));

        assertThatThrownBy(() -> actors.require("admin@ssds.dev", EnumSet.of(RoleCode.BUYER_LEAD)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void missingTokenIsUnauthorized() {
        assertThatThrownBy(() -> actors.require(null, EnumSet.of(RoleCode.BUYER_LEAD)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    private static AppUser user(String email, RoleCode role) {
        return AppUser.builder().id(1L).email(email).displayName(email)
                .roles(Set.of(Role.builder().code(role).name(role.name()).build()))
                .build();
    }
}
