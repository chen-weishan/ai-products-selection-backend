package com.example.ssds.api.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.ssds.api.risk.RiskAlertRuleCommandService.UpdateRequest;
import com.fasterxml.jackson.databind.JsonNode;

import tools.jackson.databind.json.JsonMapper;

/**
 * PUT /risks/rules/{code} 請求體的反序列化回歸測試。
 *
 * <p>Spring 7 的 HTTP 轉換器使用 Jackson 3（{@code tools.jackson}）。UpdateRequest 曾以 Jackson 2 的
 * {@code JsonNode} 作為 threshold 型別，Jackson 3 無法建構該抽象型別，導致端點對任何內容都回 500。
 * 單元測試直接呼叫 service、不經過 HTTP 反序列化，所以當時沒有被抓到；這裡補上這一層。
 */
class RiskRuleUpdateRequestJsonTest {

    /** 與 Spring 在 HTTP 層實際使用的是同一個函式庫（Jackson 3）。 */
    private final JsonMapper httpMapper = JsonMapper.builder().build();

    /** service 內部用來轉成 JsonNode 做驗證的 Jackson 2 mapper。 */
    private final com.fasterxml.jackson.databind.ObjectMapper internalMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Test
    @DisplayName("整數門檻可由 Jackson 3 解析，轉成 JsonNode 後仍是整數")
    void integerThresholdSurvivesHttpDeserialization() {
        UpdateRequest request = httpMapper.readValue(
                "{\"categoryId\":null,\"threshold\":{\"daysBeforeLeadTimeCutoff\":8}}",
                UpdateRequest.class);

        assertThat(request.categoryId()).isNull();
        assertThat(request.maxPenalty()).isNull();
        assertThat(request.threshold()).containsEntry("daysBeforeLeadTimeCutoff", 8);

        JsonNode node = internalMapper.valueToTree(request.threshold());
        assertThat(node.isObject()).isTrue();
        assertThat(node.get("daysBeforeLeadTimeCutoff").isIntegralNumber()).isTrue();
    }

    @Test
    @DisplayName("小數門檻與 maxPenalty 解析後數值不變")
    void decimalThresholdAndMaxPenaltyAreParsed() {
        UpdateRequest request = httpMapper.readValue(
                "{\"categoryId\":12,\"threshold\":{\"slope7dThreshold\":-0.40},\"maxPenalty\":10}",
                UpdateRequest.class);

        assertThat(request.categoryId()).isEqualTo(12L);
        assertThat(request.maxPenalty()).isEqualByComparingTo(BigDecimal.TEN);

        JsonNode node = internalMapper.valueToTree(request.threshold());
        assertThat(node.get("slope7dThreshold").decimalValue()).isEqualByComparingTo(new BigDecimal("-0.40"));
    }

    @Test
    @DisplayName("非整數的天數仍會被驗證層判為非整數（7.5 不可被當成整數）")
    void fractionalValueIsNotIntegral() {
        UpdateRequest request = httpMapper.readValue(
                "{\"threshold\":{\"daysBeforeLeadTimeCutoff\":7.5}}", UpdateRequest.class);

        JsonNode node = internalMapper.valueToTree(request.threshold());
        assertThat(node.get("daysBeforeLeadTimeCutoff").isIntegralNumber()).isFalse();
    }

    @Test
    @DisplayName("巢狀陣列（如 LOGISTICS_RISK 的 conditions）可以保留")
    void nestedArrayIsPreserved() {
        UpdateRequest request = httpMapper.readValue(
                "{\"threshold\":{\"conditions\":[\"CHILLED\",\"FROZEN\"],\"coldChainPoints\":4},\"maxPenalty\":10}",
                UpdateRequest.class);

        JsonNode node = internalMapper.valueToTree(request.threshold());
        assertThat(node.get("conditions").isArray()).isTrue();
        assertThat(node.get("conditions")).hasSize(2);
        assertThat(request.threshold()).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("沒有 threshold 時解析為 null，交由 service 回 400")
    void missingThresholdIsNull() {
        UpdateRequest request = httpMapper.readValue("{\"categoryId\":null}", UpdateRequest.class);

        assertThat(request.threshold()).isNull();
    }
}
