package com.example.ssds.ai.schema;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.model.*;
import com.example.ssds.core.domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

public class RecommendationResponseParserTest {
    private final RecommendationResponseParser parser =
            new RecommendationResponseParser(new ObjectMapper());

    @Test
    void acceptsCleanOutputUsingOnlyWhitelistedNumbers() {
        RecommendationOutput output = parser.parse(validJson(), input());

        assertEquals(DecisionType.ADOPT, output.action());
        assertEquals(200, output.qtyMin());
        assertEquals(300, output.qtyMax());
    }

    @Test
    void rejectsMarkdownAndTrailingText() {
        assertThrows(AiSchemaValidationException.class, () -> parser.parse(
                "```json\n" + validJson() + "\n```", input()));
        assertThrows(AiSchemaValidationException.class, () -> parser.parse(
                validJson() + "\n完成", input()));
    }

    @Test
    void rejectsQuantityOutsideBackendCandidates() {
        assertThrows(AiSchemaValidationException.class, () -> parser.parse(
                validJson()
                        .replace("\"qtyMax\":300", "\"qtyMax\":250")
                        .replace("200–300", "200–250"),
                input()));
    }

    @Test
    void rejectsAnyReasoningNumberAbsentFromInput() {
        assertThrows(AiSchemaValidationException.class, () -> parser.parse(
                validJson().replace("扣分小計為 4", "建議 7 日後再檢查，扣分小計為 4"),
                input()));
    }

    public static RecommendationInput input() {
        return new RecommendationInput(
                101L,
                List.of(
                        factor(FactorCode.TREND, "96"),
                        factor(FactorCode.MARGIN, "88"),
                        factor(FactorCode.CVR, "90"),
                        factor(FactorCode.PRICE_FIT, "82"),
                        factor(FactorCode.FESTIVAL, "85"),
                        factor(FactorCode.CLIMATE, "44")),
                new BigDecimal("86.89"),
                new BigDecimal("4.00"),
                Grade.B,
                SceneType.VIRAL,
                List.of(FactorCode.LOGISTICS_RISK),
                new RecommendationInput.FestivalWindow("MID_AUTUMN", "中秋節", 30),
                List.of(0, 200, 300));
    }

    private static RecommendationInput.FactorPercentile factor(FactorCode code, String value) {
        return new RecommendationInput.FactorPercentile(code, new BigDecimal(value), true);
    }

    public static String validJson() {
        return """
                {
                  "action":"ADOPT",
                  "qtyMin":200,
                  "qtyMax":300,
                  "quantityText":"建議首批 200–300 件",
                  "reasoning":"加分小計為 86.89，扣分小計為 4，建議小量試單。"
                }
                """;
    }
}
