package com.example.ssds.infra.entity;

import jakarta.persistence.*;
import lombok.*;

/** 供應商（規格書 §7.2 supplier）。B 軌品項成案前不綁供應商。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "supplier")
public class Supplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 50)
    private String contact;

    @Column(length = 30)
    private String phone;

    @Column(length = 500)
    private String note;
}
