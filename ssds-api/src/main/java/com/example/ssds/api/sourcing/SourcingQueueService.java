package com.example.ssds.api.sourcing;

import com.example.ssds.api.sourcing.dto.SourcingQueueFilter;
import com.example.ssds.api.sourcing.dto.SourcingQueueItemResponse;
import com.example.ssds.api.sourcing.dto.SourcingQueueResponse;
import com.example.ssds.api.sourcing.dto.SourcingQueueSummaryResponse;
import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.infra.dao.SourcingQueueDao;
import com.example.ssds.infra.dao.projection.SourcingQueueCounts;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SourcingQueueService {

    private final SourcingQueueDao queueDao;

    public SourcingQueueService(SourcingQueueDao queueDao) {
        this.queueDao = queueDao;
    }

    public SourcingQueueResponse getQueue(
            SourcingQueueFilter filter, int page, int size) {
        Set<SourcingStatus> statuses = statuses(filter);
        var content = queueDao.findPage(statuses, page, size).stream()
                .map(row -> new SourcingQueueItemResponse(
                        row.productId(), row.keyword(), row.heatStage(), row.stageWeeks(),
                        row.estimatedLifespanDays(), row.leadTimeDays(), row.timeGapDays(),
                        row.sourcingStatus()))
                .toList();
        SourcingQueueCounts counts = queueDao.count(statuses);
        int totalPages = counts.totalElements() == 0
                ? 0
                : (int) Math.ceil((double) counts.totalElements() / size);
        return new SourcingQueueResponse(
                content,
                page,
                size,
                counts.totalElements(),
                totalPages,
                new SourcingQueueSummaryResponse(
                        counts.activeCount(), counts.rejectedCount(), counts.promotedCount()));
    }

    private static Set<SourcingStatus> statuses(SourcingQueueFilter filter) {
        return switch (filter) {
            case ALL -> Set.of();
            case ACTIVE -> Set.of(
                    SourcingStatus.PENDING, SourcingStatus.SOURCING, SourcingStatus.URGENT);
            case URGENT -> Set.of(SourcingStatus.URGENT);
            case SOURCING -> Set.of(SourcingStatus.SOURCING);
            case PENDING -> Set.of(SourcingStatus.PENDING);
            case PROMOTED -> Set.of(SourcingStatus.PROMOTED);
            case REJECTED -> Set.of(SourcingStatus.REJECTED);
        };
    }
}
