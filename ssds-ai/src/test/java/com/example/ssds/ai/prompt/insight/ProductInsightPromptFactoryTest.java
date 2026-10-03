package com.example.ssds.ai.prompt.insight;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.model.insight.ProductInsightInput;
import com.example.ssds.core.domain.Season;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductInsightPromptFactoryTest {
    @Test
    void promptContainsOnlyWhitelistedStructuredInputAndForbidsTools() {
        ProductInsightPromptFactory factory = new ProductInsightPromptFactory(new ObjectMapper());
        ProductInsightInput input = new ProductInsightInput(
                101L,
                new ProductInsightInput.ProductBasic("商品", "零食", Season.ALL, "常溫"),
                List.of(new ProductInsightInput.ReviewText(1L, "口感很好")),
                List.of());

        String userPrompt = factory.userPrompt(input);
        String systemPrompt = factory.systemPrompt();

        assertTrue(userPrompt.startsWith("{"));
        assertTrue(userPrompt.contains("reviews"));
        assertFalse(userPrompt.contains("cost"));
        assertFalse(userPrompt.contains("suggestedPrice"));
        assertFalse(userPrompt.contains("margin"));
        assertFalse(userPrompt.contains("supplier"));
        assertTrue(systemPrompt.contains("不得搜尋網路或呼叫工具"));
        assertTrue(systemPrompt.contains("不得對特定品牌或供應商作出評價性斷言"));
        assertTrue(systemPrompt.contains("資料不足：評論未提供足夠資訊判定主要賣點"));
        assertTrue(systemPrompt.contains("資料不足：評論未提供足夠資訊判定其他賣點"));
        assertTrue(systemPrompt.contains("資料不足：評論未提供足夠資訊判定主要風險"));
        assertTrue(systemPrompt.contains("資料不足：評論未提供足夠資訊判定其他風險"));
        assertTrue(systemPrompt.contains("penalties 只決定 countedInPenalty，不能當成評論證據"));
        assertEquals("product-insight-v5", ProductInsightPromptFactory.PROMPT_VERSION);
        String retryInstruction = factory.retryInstruction("SUPPORT_INVALID");
        assertTrue(retryInstruction.contains("SUPPORT_INVALID"));
        assertTrue(retryInstruction.contains("supportCount"));
        assertTrue(retryInstruction.contains("countedInPenalty"));
        assertTrue(retryInstruction.contains("主要賣點"));
        assertTrue(retryInstruction.contains("其他風險"));
    }
}
