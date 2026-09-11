package com.example.ssds.api.aitask;

import static org.mockito.Mockito.*;

import com.example.ssds.core.domain.AiTaskType;
import com.example.ssds.infra.repository.AiBudgetUsageDailyRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PersistentAiBudgetUsageRecorderTest {
    @Test
    void atomicallyIncrementsTheRequestedDailyPool() {
        AiBudgetUsageDailyRepository usages = mock(AiBudgetUsageDailyRepository.class);
        LocalDate date = LocalDate.of(2026, 9, 10);

        new PersistentAiBudgetUsageRecorder(usages)
                .record(date, AiTaskType.BudgetPool.TRACK_B, 1, 0);

        verify(usages).increment(date, "TRACK_B", 1, 0);
    }
}
