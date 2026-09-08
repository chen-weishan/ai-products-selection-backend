package com.example.ssds.ai.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SourcingKeywordNormalizerTest {
    @Test
    void normalizesCompatibilityCharactersCaseAndWhitespace() {
        assertEquals("organic snack", SourcingKeywordNormalizer.normalize("  Ｏｒｇａｎｉｃ\u3000  SNACK  "));
    }

    @Test
    void removesWhitespaceInsertedBetweenHanCharacters() {
        assertEquals("熟凍帝王蟹", SourcingKeywordNormalizer.normalize(" 熟凍  帝王蟹"));
    }

    @Test
    void nullAndWhitespaceOnlyValuesBecomeEmpty() {
        assertEquals("", SourcingKeywordNormalizer.normalize(null));
        assertEquals("", SourcingKeywordNormalizer.normalize(" \t\u3000 "));
    }
}
