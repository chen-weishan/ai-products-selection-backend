package com.example.ssds.ai.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NumericTokenValidatorTest {
    @Test
    void usesExactTokensInsteadOfSubstringMatching() {
        Set<String> allowed = NumericTokenValidator.tokensFrom("樣本數 200");

        assertDoesNotThrow(() -> NumericTokenValidator.requireAllowed("樣本數 200", allowed, "output"));
        assertThrows(AiSchemaValidationException.class,
                () -> NumericTokenValidator.requireAllowed("樣本數 20", allowed, "output"));
    }

    @Test
    void recognizesNumbersAdjacentToChineseButIgnoresAsciiIdentifiers() {
        assertEquals(Set.of("12"), NumericTokenValidator.tokensFrom("連續12週，產品ISO9001與3C不視為數值敘述"));
    }

    @Test
    void preservesSignScaleAndPercentMarker() {
        Set<String> allowed = NumericTokenValidator.tokensFrom("斜率 -0.10，比例 20%");
        NumericTokenValidator.add(allowed, new BigDecimal("0.50"));

        assertDoesNotThrow(() -> NumericTokenValidator.requireAllowed(
                "斜率 -0.10，比例 20%，權重 0.50", allowed, "output"));
        assertThrows(AiSchemaValidationException.class,
                () -> NumericTokenValidator.requireAllowed("比例 20", allowed, "output"));
        assertThrows(AiSchemaValidationException.class,
                () -> NumericTokenValidator.requireAllowed("權重 0.5", allowed, "output"));
    }

    @Test
    void numericEquivalenceAllowsScaleButNotPercentConversion() {
        Set<String> allowed = NumericTokenValidator.equivalentTokensFrom("百分位 88.00");

        assertDoesNotThrow(() -> NumericTokenValidator.requireEquivalent("百分位 88", allowed, "output"));
        assertThrows(AiSchemaValidationException.class,
                () -> NumericTokenValidator.requireEquivalent("百分位 88%", allowed, "output"));
    }
}
