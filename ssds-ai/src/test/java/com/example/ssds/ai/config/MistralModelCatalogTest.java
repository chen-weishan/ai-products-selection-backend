package com.example.ssds.ai.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MistralModelCatalogTest {
    @Test
    void resolvesEveryLogicalAliasFromOneCatalog() {
        MistralModelCatalog catalog = new MistralModelCatalog(
                "classify-primary", "classify-backup",
                "long-primary", "long-backup",
                "short-primary", "short-backup",
                "numeric-primary", "numeric-backup",
                "reasoning-primary", "reasoning-backup");

        assertAll(
                () -> assertEquals("classify-primary", catalog.classify().primary()),
                () -> assertEquals("long-primary", catalog.longText().primary()),
                () -> assertEquals("short-primary", catalog.shortGeneration().primary()),
                () -> assertEquals("numeric-primary", catalog.numeric().primary()),
                () -> assertEquals("reasoning-primary", catalog.reasoning().primary()));
    }

    @Test
    void rejectsBlankPrimaryModel() {
        assertThrows(IllegalArgumentException.class, () -> new MistralModelCatalog(
                " ", "classify-backup",
                "long-primary", "long-backup",
                "short-primary", "short-backup",
                "numeric-primary", "numeric-backup",
                "reasoning-primary", "reasoning-backup"));
    }

    @Test
    void modelChainTrimsDropsBlanksAndPreservesUniqueOrder() {
        MistralModelCatalog.ModelChain chain = new MistralModelCatalog.ModelChain(
                " primary ", " fallback, primary, , second ");

        assertEquals(List.of("primary", "fallback", "second"), chain.models());
    }
}
