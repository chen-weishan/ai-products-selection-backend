package com.example.ssds.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** §6.7.2 logical aliases resolved from one Spring configuration source. */
@Component
public class MistralModelCatalog {
    private final ModelChain classify;
    private final ModelChain longText;
    private final ModelChain shortGeneration;
    private final ModelChain numeric;
    private final ModelChain reasoning;

    public MistralModelCatalog(
            @Value("${mistral.model-classify-primary}") String classifyPrimary,
            @Value("${mistral.model-classify-fallbacks}") String classifyFallbacks,
            @Value("${mistral.model-long-text-primary}") String longTextPrimary,
            @Value("${mistral.model-long-text-fallbacks}") String longTextFallbacks,
            @Value("${mistral.model-short-gen-primary}") String shortGenerationPrimary,
            @Value("${mistral.model-short-gen-fallbacks}") String shortGenerationFallbacks,
            @Value("${mistral.model-numeric-primary}") String numericPrimary,
            @Value("${mistral.model-numeric-fallbacks}") String numericFallbacks,
            @Value("${mistral.model-reasoning-primary}") String reasoningPrimary,
            @Value("${mistral.model-reasoning-fallbacks}") String reasoningFallbacks) {
        classify = new ModelChain(classifyPrimary, classifyFallbacks);
        longText = new ModelChain(longTextPrimary, longTextFallbacks);
        shortGeneration = new ModelChain(shortGenerationPrimary, shortGenerationFallbacks);
        numeric = new ModelChain(numericPrimary, numericFallbacks);
        reasoning = new ModelChain(reasoningPrimary, reasoningFallbacks);
    }

    public ModelChain classify() { return classify; }
    public ModelChain longText() { return longText; }
    public ModelChain shortGeneration() { return shortGeneration; }
    public ModelChain numeric() { return numeric; }
    public ModelChain reasoning() { return reasoning; }

    public record ModelChain(String primary, String fallbacks) {
        public ModelChain {
            if (primary == null || primary.isBlank()) {
                throw new IllegalArgumentException("Mistral primary model must not be blank");
            }
            primary = primary.trim();
            fallbacks = fallbacks == null ? "" : fallbacks.trim();
        }
    }
}
