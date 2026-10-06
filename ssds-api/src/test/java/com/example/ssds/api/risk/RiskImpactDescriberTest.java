package com.example.ssds.api.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ssds.core.domain.ProductStatus;

/** S-11「影響」欄文字（對照畫面稿範例）。 */
class RiskImpactDescriberTest {

    @Test
    @DisplayName("扣分類：扣分小計 < 20 → 未壓級")
    void penaltyBelowCap() {
        assertThat(RiskImpactDescriber.describe(
                RiskTypes.REVIEW_RISK, ProductStatus.WATCHING, new BigDecimal("8.0"), new BigDecimal("8.0")))
                .isEqualTo("扣 8 分（扣分小計 < 20，未壓級）");
    }

    @Test
    @DisplayName("扣分類：扣分小計 ≥ 20 → 已壓級")
    void penaltyAtCap() {
        assertThat(RiskImpactDescriber.describe(
                RiskTypes.REVIEW_RISK, ProductStatus.WATCHING, new BigDecimal("20"), new BigDecimal("22")))
                .isEqualTo("扣 20 分（扣分小計 ≥ 20，已壓級）");
    }

    @Test
    @DisplayName("扣分類：查不到最新評分時退回通用文字")
    void penaltyWithoutScore() {
        assertThat(RiskImpactDescriber.describe(
                RiskTypes.LOGISTICS_RISK, ProductStatus.WATCHING, null, null))
                .isEqualTo("計入風險扣分");
    }

    @Test
    @DisplayName("熱度急墜與季節不匹配依品項狀態帶出已上架／已採納")
    void statusAwareTexts() {
        assertThat(RiskImpactDescriber.describe(RiskTypes.HEAT_CRASH, ProductStatus.LISTED, null, null))
                .isEqualTo("已上架品項熱度急墜");
        assertThat(RiskImpactDescriber.describe(RiskTypes.SEASON_MISMATCH, ProductStatus.ADOPTED, null, null))
                .isEqualTo("已採納品項季節錯位");
    }

    @Test
    @DisplayName("其餘類型為固定文字")
    void fixedTexts() {
        assertThat(RiskImpactDescriber.describe(RiskTypes.PENALTY_CAP, ProductStatus.ADOPTED, null, null))
                .isEqualTo("分級鎖定最高 B（§FR-04）");
        assertThat(RiskImpactDescriber.describe(RiskTypes.FESTIVAL_WINDOW_CLOSING, ProductStatus.WATCHING, null, null))
                .isEqualTo("尚未建立決策");
        assertThat(RiskImpactDescriber.describe(RiskTypes.LOW_CONFIDENCE, ProductStatus.WATCHING, null, null))
                .isEqualTo("分數僅供參考");
        assertThat(RiskImpactDescriber.describe(RiskTypes.DATA_INSUFFICIENT, ProductStatus.DRAFT, null, null))
                .isEqualTo("資料不足，無法產生分數");
    }

    @Test
    @DisplayName("每個合法的 risk_type 都有影響文字")
    void everyTypeHasText() {
        for (String type : RiskTypes.ALL) {
            assertThat(RiskImpactDescriber.describe(type, ProductStatus.LISTED, null, null))
                    .as(type).isNotBlank();
        }
    }
}
