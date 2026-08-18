package com.example.ssds.infra.repository;

import com.example.ssds.core.domain.RoleCode;
import com.example.ssds.infra.entity.Role;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 角色查詢（規格書 §7.2 role）。 */
@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByCode(RoleCode code);
}
