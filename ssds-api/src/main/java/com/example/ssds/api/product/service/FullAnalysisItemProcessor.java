package com.example.ssds.api.product.service;

import com.example.ssds.core.domain.TaskItemStatus;
import com.example.ssds.infra.entity.AiTaskItem;
import com.example.ssds.infra.repository.AiTaskItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 每個品項獨立交易，單筆失敗不回滾整批。 */
@Service
public class FullAnalysisItemProcessor {

    private final AiTaskItemRepository itemRepository;
    private final ProductFallbackScoringService scoringService;

    public FullAnalysisItemProcessor(
            AiTaskItemRepository itemRepository,
            ProductFallbackScoringService scoringService
    ) {
        this.itemRepository = itemRepository;
        this.scoringService = scoringService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long itemId) {
        long started = System.nanoTime();
        AiTaskItem item = itemRepository.findForProcessing(itemId).orElseThrow();
        if (item.getProduct() == null || !item.getProduct().isScorable()) {
            throw new IllegalStateException("FULL_ANALYSIS 僅支援 A 軌品項");
        }
        scoringService.score(item.getProduct());
        item.setStatus(TaskItemStatus.SUCCEEDED);
        item.setErrorMessage(null);
        item.setDurationMs(elapsedMillis(started));
    }

    static int elapsedMillis(long started) {
        return Math.toIntExact(Math.min((System.nanoTime() - started) / 1_000_000L, Integer.MAX_VALUE));
    }
}
