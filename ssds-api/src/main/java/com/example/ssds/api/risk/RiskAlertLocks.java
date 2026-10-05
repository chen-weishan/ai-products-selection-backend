package com.example.ssds.api.risk;

import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 以「品項＋示警類型」為粒度的交易級 advisory lock，擋住去重的「先查再寫」競態。
 *
 * <p>同一品項同一類型的示警可能被兩條路徑同時產生（單筆評分、06:30 熱度排程）。
 * 兩邊都先查到「窗內沒有」再各自新增，就會出現兩筆 OPEN。這裡讓後到的交易
 * 在鎖上等候，等前一個交易提交後才查詢，就會看到對方剛寫入的那一筆而改為更新。
 *
 * <p>鎖隨交易結束自動釋放，不需要手動解鎖；必須在交易內呼叫。
 */
@Component
public class RiskAlertLocks {

    @PersistenceContext
    private EntityManager entityManager;

    public void lock(Long productId, String riskType) {
        entityManager
                .createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .setParameter("key", "risk_alert:" + productId + ":" + riskType)
                .getSingleResult();
    }
}
