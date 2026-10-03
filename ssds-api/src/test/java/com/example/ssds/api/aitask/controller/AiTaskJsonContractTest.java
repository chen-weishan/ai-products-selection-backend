package com.example.ssds.api.aitask.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssds.api.aibudget.AiBudgetController;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;

class AiTaskJsonContractTest {

    @Test
    void aiTaskAndBudgetControllersDeclareJsonResponsesForOpenApiGeneration() {
        assertProducesJson(AiTaskController.class);
        assertProducesJson(AiBudgetController.class);
    }

    private static void assertProducesJson(Class<?> controllerType) {
        RequestMapping mapping = controllerType.getAnnotation(RequestMapping.class);
        assertTrue(
                mapping != null
                        && Arrays.asList(mapping.produces()).contains(MediaType.APPLICATION_JSON_VALUE),
                () -> controllerType.getSimpleName() + " must declare application/json responses");
    }
}
