package com.example.ssds.infra.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.infra.event.SalesImportCompletedEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

class BulkImportDaoTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final BulkImportDao dao = new BulkImportDao(jdbc, events);

    @Test
    void publishesEachCompletedSalesImportBatchOnce() {
        when(jdbc.batchUpdate(anyString(), anyList(), anyInt(), any()))
                .thenReturn(new int[][] {{1, 1, 1}});
        List<BulkImportDao.SalesRow> rows = List.of(
                row(1L, 91L),
                row(2L, 91L),
                row(3L, 92L));

        int inserted = dao.batchInsertSalesRecords(rows);

        assertEquals(3, inserted);
        ArgumentCaptor<SalesImportCompletedEvent> event =
                ArgumentCaptor.forClass(SalesImportCompletedEvent.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(event.capture());
        assertEquals(List.of(91L, 92L), event.getAllValues().stream()
                .map(SalesImportCompletedEvent::importBatchId)
                .toList());
    }

    @Test
    void emptySalesImportDoesNotPublishACompletionEvent() {
        assertEquals(0, dao.batchInsertSalesRecords(List.of()));

        verify(events, never()).publishEvent(any());
    }

    private static BulkImportDao.SalesRow row(Long productId, Long batchId) {
        return new BulkImportDao.SalesRow(
                LocalDate.of(2026, 9, 18),
                productId,
                "product-" + productId,
                10L,
                new BigDecimal("100.00"),
                1,
                10,
                "GENERAL",
                batchId);
    }
}
