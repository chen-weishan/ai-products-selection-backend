package com.example.ssds.core.domain;

/** 品項狀態（規格書 §7.2 product.status）。 */
public enum ProductStatus {
    /** 草稿，資料尚未齊全 */
    DRAFT,
    /** 評估中 */
    EVALUATING,
    /** 觀察中 */
    WATCHING,
    /** 已採納 */
    ADOPTED,
    /** 已上架開團 */
    LISTED,
    /** 已淘汰 */
    REJECTED
}
