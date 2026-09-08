package com.example.ssds.ai.prompt;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.schema.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Locks the human-readable prompt contract to the same root fields enforced by each response parser. */
class PromptSchemaContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> everyAgentDeclaresCommonSafetyContractAndSchemaFields() {
        return contracts().stream().map(contract -> DynamicTest.dynamicTest(contract.name(), () -> {
            String prompt = contract.prompt().get();
            JsonNode schema = contract.schema().get();

            assertAll(
                    () -> assertTrue(prompt.contains("零售團購採購決策輔助系統")),
                    () -> assertTrue(prompt.contains("INPUT_JSON")),
                    () -> assertTrue(prompt.contains("JSON")),
                    () -> assertTrue(prompt.contains("資料不足")),
                    () -> assertTrue(prompt.contains("品牌")),
                    () -> assertTrue(prompt.contains("供應商")),
                    () -> assertTrue(schema.path("properties").isObject()),
                    () -> assertFalse(schema.path("additionalProperties").asBoolean(true)));

            schema.path("properties").fieldNames().forEachRemaining(field ->
                    assertTrue(prompt.contains(field),
                            () -> contract.name() + " prompt 未宣告 Schema 欄位: " + field));
            schema.findValues("enum").stream()
                    .flatMap(JsonNode::valueStream)
                    .filter(JsonNode::isTextual)
                    .map(JsonNode::asText)
                    .forEach(value -> assertTrue(prompt.contains(value),
                            () -> contract.name() + " prompt 未宣告 Schema 列舉: " + value));
        }));
    }

    @Test
    void sourcingUsesToolAwareContractWithoutRequiringRequestSideResponseFormat() {
        String prompt = new SourcingScoutPromptFactory(mapper).systemPrompt();

        assertAll(
                () -> assertTrue(prompt.contains("必須先使用本次提供的搜尋 Connector")),
                () -> assertTrue(prompt.contains("只能根據 INPUT_JSON 與本次搜尋結果")),
                () -> assertTrue(prompt.contains("只能輸出一個合法 JSON object")));
    }

    @Test
    void productInsightRequiresSupportCountForBothEvidenceLists() {
        JsonNode schema = ProductInsightSchema.create(mapper);

        assertAll(
                () -> assertTrue(schema.at("/properties/sellingPoints/items/required")
                        .valueStream().anyMatch(value -> "supportCount".equals(value.asText()))),
                () -> assertTrue(schema.at("/properties/risks/items/required")
                        .valueStream().anyMatch(value -> "supportCount".equals(value.asText()))),
                () -> assertTrue(new ProductInsightPromptFactory(mapper).systemPrompt()
                        .contains("支持該風險的則數")));
    }

    private List<Contract> contracts() {
        return List.of(
                new Contract("Agent 1", new SceneClassifierPromptFactory(mapper)::systemPrompt,
                        () -> SceneClassifierSchema.create(mapper)),
                new Contract("Agent 2", new ReviewRiskPromptFactory(mapper)::systemPrompt,
                        () -> ReviewRiskSchema.create(mapper)),
                new Contract("Agent 3", new ProductInsightPromptFactory(mapper)::systemPrompt,
                        () -> ProductInsightSchema.create(mapper)),
                new Contract("Agent 4", new RecommendationPromptFactory(mapper)::systemPrompt,
                        () -> RecommendationSchema.create(mapper)),
                new Contract("Agent 5", new TrendInterpreterPromptFactory(mapper)::systemPrompt,
                        () -> TrendInterpreterSchema.create(mapper)),
                new Contract("Agent 6", new SourcingScoutPromptFactory(mapper)::systemPrompt,
                        () -> SourcingScoutSchema.create(mapper)),
                new Contract("Agent 7", new WeightCalibrationPromptFactory(mapper)::systemPrompt,
                        () -> WeightCalibrationSchema.create(mapper)));
    }

    private record Contract(String name, Supplier<String> prompt, Supplier<JsonNode> schema) {}
}
