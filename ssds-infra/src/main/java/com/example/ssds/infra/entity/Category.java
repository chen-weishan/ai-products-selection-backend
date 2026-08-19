package com.example.ssds.infra.entity;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import lombok.*;

/**
 * 品類（規格書 §7.2 category）。
 *
 * <p>§5.3.1 的同品類百分位正規化以本表為分組依據，因此類別的粒度直接
 * 決定分數的可比性 —— 分得太粗會讓不同性質的商品互相比較，
 * 分得太細則同品類樣本數不足 10 筆，觸發 §5.7 的降級處理。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "category")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 自我參照，支援兩層類別。NULL 表頂層。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @OneToMany(mappedBy = "parent")
    @Builder.Default
    private List<Category> children = new ArrayList<>();

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    public boolean isRoot() {
        return parent == null;
    }
}
