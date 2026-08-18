package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.RoleCode;
import jakarta.persistence.*;
import lombok.*;

/** 角色主檔（規格書 §7.2 role）。權限矩陣見 §2.1。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "role")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 不含 ROLE_ 前綴；Spring Security 的 hasRole() 會自行補上。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 32)
    private RoleCode code;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 200)
    private String description;

    /** 轉成 Spring Security 的 authority 字串。 */
    public String toAuthority() {
        return "ROLE_" + code.name();
    }
}
