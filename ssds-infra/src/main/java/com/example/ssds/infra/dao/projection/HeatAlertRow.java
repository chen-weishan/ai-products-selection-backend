package com.example.ssds.infra.dao.projection;

import java.math.BigDecimal;

/**
 * 熱度示警偵測的一列：某品項「關聯到的某個關鍵字」在偵測日的合成熱度斜率。
 *
 * <p>一個品項可關聯多個關鍵字（{@code product_keyword} 多對多），所以同一品項會有多列；
 * 由 {@code HeatAlertDao} 依 {@code slope_7d} 由大到小排序回傳，品項的第一列就是生效關鍵字。
 *
 * @param parentCategoryId 品類的父品類，頂層品類為 null（供樣本不足時合併兄弟品類）
 * @param status           {@code product.status} 的字串值
 */
public record HeatAlertRow(
        Long productId,
        Long categoryId,
        Long parentCategoryId,
        String status,
        Long keywordId,
        String keyword,
        BigDecimal slope7d,
        boolean volumeBelowFloor) {
}
