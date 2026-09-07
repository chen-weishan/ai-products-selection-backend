package com.example.ssds.api.config;

import java.util.List;

/**
 * Instagram hashtag → 品類名稱的對照，直接寫死在程式碼裡（不進資料庫、
 * 也不進 application.properties）。
 *
 * <p>刻意不用 {@code @ConfigurationProperties}／application.properties：
 * 那個檔案是團隊共用設定，這張對照表目前只有個位數筆、變動也不頻繁，
 * 用一支獨立的 Java 類別維護，異動範圍完全侷限在這一支新檔案，
 * 不會動到既有的資料庫 schema 或團隊共用的設定檔。
 *
 * <p>代價：改對照關係要改這支程式碼、重新編譯部署，不能像設定檔一樣
 * 改完直接重啟生效。之後如果對照表變大或需要讓非工程師自己調整，
 * 再考慮遷到設定檔或資料庫欄位。
 *
 * <p>{@code categoryName} 要跟 {@code category.name} 完全一致（忽略大小寫）；
 * 查無或查到多筆同名品類的項目，排程執行時會被跳過並記警告，不會讓整個
 * 排程失敗（見 {@code InstagramHeatIngestJob}）。
 */
public final class InstagramHashtagMapping {

    /** 要新增／調整追蹤的 hashtag，直接改這個清單即可。 */
    public static final List<Entry> ENTRIES = List.of(
            new Entry("skincare", "零食")
    );

    private InstagramHashtagMapping() {}

    public record Entry(String hashtag, String categoryName) {}
}