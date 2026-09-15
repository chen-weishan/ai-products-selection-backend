package com.example.ssds.api.score;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.ssds.infra.entity.SceneClassificationLog;
import com.example.ssds.infra.repository.SceneClassificationLogRepository;

import lombok.RequiredArgsConstructor;

/**
 * 「情境判定經人工覆寫」的批次查詢（§FR-04 顯示內容表：情境判定「經人工覆寫者附標記」）。
 *
 * <p>排行與試算都要這份旗標，抽成一支共用元件而不是各寫一份——
 * 「最新一筆才算數」這條規則寫錯兩次的成本遠高於多一個類別。
 */
@Component
@RequiredArgsConstructor
class SceneOverrideLookup {

    private final SceneClassificationLogRepository sceneClassificationLogRepository;

    /**
     * @param period     排行所屬的年月（{@code YYYY-MM}）
     * @param productIds 這一頁的品項 id
     * @return 其中「該 period 最新一筆判定為人工覆寫」的品項 id
     */
    @Transactional(readOnly = true)
    Set<Long> overriddenProductIds(String period, Collection<Long> productIds) {

        // 空集合短路：不做這件事會送出 in () 這種不合法的 SQL
        if (productIds.isEmpty()) {
            return Set.of();
        }

        // 查詢已依 createdAt 遞減排序，所以每個品項第一次出現的那筆就是最新的。
        // putIfAbsent 只留第一筆，後面的舊紀錄一律略過。
        Map<Long, SceneClassificationLog> latestByProductId = new HashMap<>();
        for (SceneClassificationLog log
                : sceneClassificationLogRepository
                        .findByPeriodAndProductIdInOrderByCreatedAtDesc(period, productIds)) {
            latestByProductId.putIfAbsent(log.getProduct().getId(), log);
        }

        Set<Long> overridden = new HashSet<>();
        latestByProductId.forEach((productId, log) -> {
            if (log.isOverridden()) {
                overridden.add(productId);
            }
        });
        return overridden;
    }
}
