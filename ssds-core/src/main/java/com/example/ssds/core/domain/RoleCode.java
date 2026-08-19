package com.example.ssds.core.domain;

/**
 * 使用者角色代碼（規格書 §2）。
 *
 * <p>Spring Security 的 hasRole("BUYER") 會自動補上 ROLE_ 前綴，
 * 因此資料庫的 role.code 存的是不含前綴的字串，授權時再由 Security 設定補齊。
 */
public enum RoleCode {
    /** 採購專員：日常評估品項、記錄決策 */
    BUYER,
    /** 採購主管：覆核決策、調整權重、看報表 */
    BUYER_LEAD,
    /** 資料管理員：匯入銷售/評論資料、維護關鍵字 */
    DATA_ADMIN,
    /** 系統管理員：使用者、模型、系統參數 */
    SYS_ADMIN,
    /** 唯讀觀察者：只能瀏覽，不能修改 */
    VIEWER
}
