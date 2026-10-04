package com.example.ssds.api.decision;

import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.InsightType;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.SceneType;
import com.example.ssds.core.domain.SelloutStatus;
import com.example.ssds.infra.entity.AiInsight;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.CampaignResult;
import com.example.ssds.infra.entity.CampaignSnapshot;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.DecisionRecord;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.entity.ProductScore;
import com.example.ssds.infra.entity.WeightVersion;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/** FR-11 測試共用的最小 entity。今日固定為 2026-09-29（Asia/Taipei）。 */
final class DecisionFixtures {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    static final Clock CLOCK = Clock.fixed(
            TODAY.atTime(10, 0).atZone(DecisionMapper.BUSINESS_ZONE).toInstant(), DecisionMapper.BUSINESS_ZONE);
    static final String BUYER_EMAIL = "buyer@ssds.local";

    private DecisionFixtures() {
    }

    static AppUser user(long id, String email) {
        return AppUser.builder().id(id).email(email).displayName("使用者" + id).build();
    }

    static Product product(long id) {
        return Product.builder()
                .id(id)
                .name("品項 " + id)
                .status(ProductStatus.EVALUATING)
                .category(Category.builder().id(1L).name("零食").build())
                .build();
    }

    static ProductScore score(long id, Product product, String finalScore, Grade grade) {
        return ProductScore.builder()
                .id(id)
                .product(product)
                .weightVersion(WeightVersion.builder().id(3L).versionNo("v3").name("v3").build())
                .period("2026W39")
                .sceneType(SceneType.REPLENISHMENT)
                .primary(true)
                .active(true)
                .bonusSubtotal(new BigDecimal(finalScore))
                .penaltySubtotal(BigDecimal.ZERO)
                .finalScore(new BigDecimal(finalScore))
                .grade(grade)
                .build();
    }

    static AiInsight recommendation(long id, String contentJson) {
        return AiInsight.builder()
                .id(id)
                .insightType(InsightType.RECOMMENDATION)
                .contentJson(contentJson)
                .build();
    }

    /** 品項狀態對齊 §7.4 建立決策後的結果；ADOPT 視為已上架（LISTED），可直接結案。 */
    static DecisionRecord decision(long id, DecisionType type) {
        Product product = product(100 + id);
        product.setStatus(switch (type) {
            case ADOPT -> ProductStatus.LISTED;
            case WATCH -> ProductStatus.WATCHING;
            case REJECT -> ProductStatus.REJECTED;
        });
        return DecisionRecord.builder()
                .id(id)
                .product(product)
                .score(score(200 + id, product, "80.00", Grade.A))
                .decision(type)
                .firstOrderQty(type == DecisionType.ADOPT ? 300 : null)
                .decidedBy(user(1L, BUYER_EMAIL))
                .decidedAt(Instant.parse("2026-09-01T02:00:00Z"))
                .build();
    }

    /** 已回填的 ADOPT，用於準確度統計。 */
    static DecisionRecord filled(long id, String finalScore, Grade grade, int actualQty, SelloutStatus sellout) {
        DecisionRecord record = decision(id, DecisionType.ADOPT);
        record.setScore(score(200 + id, record.getProduct(), finalScore, grade));
        record.setCampaignEndDate(TODAY.minusDays(20));
        record.setResult(CampaignResult.builder()
                .decisionId(id)
                .decision(record)
                .actualQty(actualQty)
                .selloutStatus(sellout)
                .realizedMarginRate(new BigDecimal("0.4000"))
                .build());
        record.setSnapshot(CampaignSnapshot.builder().decisionId(id).decision(record).build());
        return record;
    }
}
