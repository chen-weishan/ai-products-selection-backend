package com.example.ssds.api.sourcing;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.sourcing.dto.SourcingQueueFilter;
import com.example.ssds.core.domain.HeatStage;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.infra.dao.SourcingQueueDao;
import com.example.ssds.infra.dao.projection.SourcingQueueCounts;
import com.example.ssds.infra.dao.projection.SourcingQueueRow;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SourcingQueueServiceTest {

    private final SourcingQueueDao dao = mock(SourcingQueueDao.class);
    private final SourcingQueueService service = new SourcingQueueService(dao);

    @Test
    void returnsOnePageAndGlobalSummaryFromTheBatchQuery() {
        Set<SourcingStatus> statuses = Set.of(
                SourcingStatus.PENDING, SourcingStatus.SOURCING, SourcingStatus.URGENT);
        when(dao.findPage(statuses, 1, 10)).thenReturn(List.of(new SourcingQueueRow(
                21L, "候選", HeatStage.RISING, (short) 2, 56, 45, 11,
                SourcingStatus.URGENT)));
        when(dao.count(statuses)).thenReturn(new SourcingQueueCounts(21, 15, 4, 2));

        var response = service.getQueue(SourcingQueueFilter.ACTIVE, 1, 10);

        assertAll(
                () -> assertEquals(1, response.content().size()),
                () -> assertEquals(21L, response.content().getFirst().productId()),
                () -> assertEquals(11, response.content().getFirst().timeGapDays()),
                () -> assertEquals(21, response.totalElements()),
                () -> assertEquals(3, response.totalPages()),
                () -> assertEquals(15, response.summary().activeCount()),
                () -> assertEquals(4, response.summary().rejectedCount()),
                () -> assertEquals(2, response.summary().promotedCount()));
        verify(dao).findPage(statuses, 1, 10);
        verify(dao).count(statuses);
    }

    @Test
    void allFilterUsesNoStatusRestrictionAndEmptyResultHasZeroPages() {
        when(dao.findPage(Set.of(), 0, 10)).thenReturn(List.of());
        when(dao.count(Set.of())).thenReturn(new SourcingQueueCounts(0, 0, 0, 0));

        var response = service.getQueue(SourcingQueueFilter.ALL, 0, 10);

        assertEquals(0, response.totalPages());
        verify(dao).findPage(Set.of(), 0, 10);
    }
}
