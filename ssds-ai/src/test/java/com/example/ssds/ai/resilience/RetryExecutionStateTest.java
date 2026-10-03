package com.example.ssds.ai.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class RetryExecutionStateTest {
    @Test
    void schemaRetryUsesSameModelOnceThenOneFallback() {
        RetryExecutionState state = new RetryExecutionState(List.of("primary", "fallback", "unused"), 3);

        assertThat(state.beginSchemaRetry()).isTrue();
        assertThat(state.model()).isEqualTo("primary");
        assertThat(state.beginSchemaRetry()).isFalse();
        assertThat(state.moveToSchemaFallback()).isTrue();
        assertThat(state.model()).isEqualTo("fallback");
        assertThat(state.moveToSchemaFallback()).isFalse();
    }

    @Test
    void rateLimitUsesOneTwoFourSecondBackoffThenAllowsModelFallback() {
        RetryExecutionState state = new RetryExecutionState(List.of("primary", "fallback"), 3);

        assertThat(state.nextRateLimitDelay()).hasValue(1_000L);
        assertThat(state.nextRateLimitDelay()).hasValue(2_000L);
        assertThat(state.nextRateLimitDelay()).hasValue(4_000L);
        assertThat(state.nextRateLimitDelay()).isEmpty();
        assertThat(state.rateLimitRetryCount()).isEqualTo(3);
        assertThat(state.moveToNextModel()).isTrue();
        assertThat(state.model()).isEqualTo("fallback");
    }

    @Test
    void tracksOnlyRecordedExternalRequestsAndRetryInstruction() {
        RetryExecutionState state = new RetryExecutionState(List.of("primary"), 3);

        assertThat(state.isRetryAttempt()).isFalse();
        state.retryInstruction("fix schema");
        state.recordRequest();

        assertThat(state.retryInstruction()).isEqualTo("fix schema");
        assertThat(state.requestCount()).isEqualTo(1);
        assertThat(state.isRetryAttempt()).isTrue();
    }

    @Test
    void rejectsEmptyModelChain() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RetryExecutionState(List.of(), 3));
    }
}
