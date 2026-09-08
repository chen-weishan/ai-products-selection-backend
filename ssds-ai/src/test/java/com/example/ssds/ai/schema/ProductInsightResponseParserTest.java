package com.example.ssds.ai.schema;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.model.ProductInsightInput;
import com.example.ssds.core.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductInsightResponseParserTest {
    private final ProductInsightResponseParser parser =
            new ProductInsightResponseParser(new ObjectMapper());

    @Test
    void acceptsCleanCombinedOutputAndChecksPenaltyFlags() {
        var output = parser.parse(validJson(), input());

        assertEquals(2, output.sellingPoints().size());
        assertEquals(3, output.sellingPoints().getFirst().supportCount());
        assertEquals(InsightRiskType.QUALITY, output.risks().getFirst().type());
        assertTrue(output.risks().getFirst().countedInPenalty());
    }

    @Test
    void rejectsMarkdownOrAnyTextOutsideRootJson() {
        assertThrows(AiSchemaValidationException.class, () -> parser.parse(
                "```json\n" + validJson() + "\n```", input()));
        assertThrows(AiSchemaValidationException.class, () -> parser.parse(
                validJson() + "\n結束", input()));
    }

    @Test
    void rejectsPenaltyFlagThatDoesNotMatchBackendInput() {
        assertThrows(AiSchemaValidationException.class, () -> parser.parse(
                validJson().replace(
                        "\"type\":\"PRICE\",\"severity\":\"LOW\",\"countedInPenalty\":false",
                        "\"type\":\"PRICE\",\"severity\":\"LOW\",\"countedInPenalty\":true"),
                input()));
    }

    @Test
    void permitsExplicitInsufficientDataOnlyWithZeroSupport() {
        String json = validJson().replace(
                "\"口感獲得多則正面回饋\",\"supportCount\":3,\"aspect\":\"口感\"",
                "\"資料不足：無足夠評論支持具體賣點\",\"supportCount\":0,\"aspect\":\"資料不足\"");

        assertDoesNotThrow(() -> parser.parse(json, input()));
    }

    private static ProductInsightInput input() {
        return new ProductInsightInput(
                101L,
                new ProductInsightInput.ProductBasic(
                        "抹茶餅乾", "零食", Season.ALL, "常溫"),
                List.of(
                        new ProductInsightInput.ReviewText(1L, "茶味香濃"),
                        new ProductInsightInput.ReviewText(2L, "口感酥脆"),
                        new ProductInsightInput.ReviewText(3L, "包裝完整")),
                List.of(
                        new ProductInsightInput.PenaltyDetail(
                                FactorCode.REVIEW_RISK,
                                new BigDecimal("8.0"),
                                List.of("QUALITY")),
                        new ProductInsightInput.PenaltyDetail(
                                FactorCode.LOGISTICS_RISK, BigDecimal.ZERO, List.of())));
    }

    private static String validJson() {
        return """
                {
                  "sellingPoints":[
                    {"text":"口感獲得多則正面回饋","supportCount":3,"aspect":"口感"},
                    {"text":"包裝完整度受到肯定","supportCount":1,"aspect":"包裝"}
                  ],
                  "risks":[
                    {"text":"部分評論反映品質不穩定","type":"QUALITY","severity":"MEDIUM","countedInPenalty":true},
                    {"text":"價格接受度的資料有限","type":"PRICE","severity":"LOW","countedInPenalty":false}
                  ]
                }
                """;
    }
}
