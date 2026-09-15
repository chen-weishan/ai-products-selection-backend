package com.example.ssds.api.imports.service;

import com.example.ssds.api.imports.event.ImportCompletedEvent;
import java.util.LinkedHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 協調 FR-09 受影響品項重算；個別失敗不阻斷其他品項或回寫匯入批次狀態。 */
@Service
public class ImportScoreRecalculationService {

    private static final Logger log = LoggerFactory.getLogger(ImportScoreRecalculationService.class);
    private final ImportScoreRecalculationItemService itemService;

    public ImportScoreRecalculationService(ImportScoreRecalculationItemService itemService) {
        this.itemService = itemService;
    }

    public void recalculate(ImportCompletedEvent event) {
        for (Long productId : new LinkedHashSet<>(event.affectedProductIds())) {
            try {
                itemService.recalculate(productId);
            } catch (RuntimeException error) {
                log.error("FR-09 score recalculation failed: batchId={}, productId={}",
                        event.batchId(), productId, error);
            }
        }
    }
}
