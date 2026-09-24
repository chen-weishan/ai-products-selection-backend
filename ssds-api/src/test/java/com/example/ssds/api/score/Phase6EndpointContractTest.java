package com.example.ssds.api.score;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ssds.api.scene.SceneClassificationController;
import io.swagger.v3.oas.annotations.Operation;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/** 保護後續 OpenAPI Generator／Postman 匯入所依賴的 Phase 6 路徑。 */
class Phase6EndpointContractTest {

    @Test
    void phase6RoutesRemainDiscoverableBySpringdoc() {
        assertRoute(ScoreController.class, "rankingSummary", GetMapping.class, "/ranking/summary");
        assertRoute(ProductScoreController.class, "recalculate", PostMapping.class,
                "/{id}/scores/recalculate");
        assertRoute(SceneClassificationController.class, "history", GetMapping.class, "/scene-log");
        assertRoute(SceneClassificationController.class, "override", PutMapping.class, "/scene-override");
    }

    private static void assertRoute(
            Class<?> controller,
            String methodName,
            Class<? extends java.lang.annotation.Annotation> mappingType,
            String expectedPath) {
        Method method = Arrays.stream(controller.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        assertThat(method.getAnnotation(Operation.class)).isNotNull();
        if (mappingType == GetMapping.class) {
            assertThat(method.getAnnotation(GetMapping.class).value()).contains(expectedPath);
        } else if (mappingType == PostMapping.class) {
            assertThat(method.getAnnotation(PostMapping.class).value()).contains(expectedPath);
        } else {
            assertThat(method.getAnnotation(PutMapping.class).value()).contains(expectedPath);
        }
    }
}
