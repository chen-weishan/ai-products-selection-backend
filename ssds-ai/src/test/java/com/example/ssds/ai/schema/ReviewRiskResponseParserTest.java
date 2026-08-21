package com.example.ssds.ai.schema;

import static org.junit.jupiter.api.Assertions.*;

import com.example.ssds.ai.model.ReviewRiskInput;
import com.example.ssds.core.domain.ReviewRiskTopic;
import com.example.ssds.core.domain.Sentiment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewRiskResponseParserTest {
    private final ReviewRiskResponseParser parser = new ReviewRiskResponseParser(new ObjectMapper());
    private final ReviewRiskInput input = new ReviewRiskInput(
            101L,
            List.of(
                    new ReviewRiskInput.ReviewText(1L, "包裝破損"),
                    new ReviewRiskInput.ReviewText(2L, "味道不錯")));

    @Test
    void acceptsCleanJsonWithOneToOneReviewsAndConsistentRatios() {
        var output = parser.parse(validJson(), input);

        assertEquals(2, output.reviews().size());
        assertEquals(Sentiment.NEGATIVE, output.reviews().getFirst().sentiment());
        assertEquals(ReviewRiskTopic.SHIPPING_DAMAGE, output.reviews().getFirst().riskTopic());
    }

    @Test
    void rejectsMarkdownEvenWhenEmbeddedJsonIsValid() {
        assertThrows(AiSchemaValidationException.class, () -> parser.parse(
                "```json\n" + validJson() + "\n```", input));
    }

    @Test
    void rejectsRatioThatDisagreesWithPerReviewClassification() {
        assertThrows(AiSchemaValidationException.class, () -> parser.parse(
                validJson().replace("\"ratio\":1", "\"ratio\":0.5"), input));
    }

    private static String validJson() {
        return """
                {
                  "reviews": [
                    {"reviewId":1,"sentiment":"NEGATIVE","riskTopic":"SHIPPING_DAMAGE"},
                    {"reviewId":2,"sentiment":"POSITIVE","riskTopic":null}
                  ],
                  "topicStatistics": [
                    {"topic":"QUALITY","ratio":0,"severity":"LOW"},
                    {"topic":"FOOD_SAFETY","ratio":0,"severity":"LOW"},
                    {"topic":"SHIPPING_DAMAGE","ratio":1,"severity":"HIGH"},
                    {"topic":"PRICE","ratio":0,"severity":"LOW"},
                    {"topic":"OTHER","ratio":0,"severity":"LOW"}
                  ]
                }
                """;
    }
}
