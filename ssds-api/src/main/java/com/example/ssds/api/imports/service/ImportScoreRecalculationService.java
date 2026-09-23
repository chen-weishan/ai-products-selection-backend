package com.example.ssds.api.imports.service;

import com.example.ssds.api.imports.event.ImportCompletedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Task completion and score snapshot commit together. Uncommitted work survives a restart. */
@Service
public class ImportScoreRecalculationService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ImportScoreRecalculationService.class);
    private final JdbcTemplate jdbc;
    private final ImportScoreRecalculationItemService itemService;
    private final TransactionTemplate transaction;
    public ImportScoreRecalculationService(JdbcTemplate jdbc,ImportScoreRecalculationItemService itemService,
            PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.itemService=itemService;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(60);
    }
    public void recalculate(ImportCompletedEvent event) {
        // The event is only a wake-up signal; the database is the durable source of work.
        for(int i=0;i<20 && processOne(event.batchId());i++) {}
    }
    @Scheduled(fixedDelayString="${ssds.import.recalculation-delay:15s}")
    public void recover() {
        try { for(int i=0;i<20 && processOne(null);i++) {} }
        catch(RuntimeException error) {log.warn("FR09 重算工作暫時無法讀取，下次排程重試",error);}
    }
    boolean processOne(Long batchId) {
        Task[] selected={null};
        try {
            return Boolean.TRUE.equals(transaction.execute(status -> {
                var tasks=jdbc.query("""
                    select t.batch_id,t.product_id from import_recalculation_task t
                    join import_batch b on b.id=t.batch_id
                    where t.status='PENDING' and t.next_attempt_at<=now()
                      and b.status not in ('PENDING','RUNNING')
                    """+(batchId==null ? "" : " and t.batch_id="+batchId.longValue())+
                    " order by t.next_attempt_at,t.batch_id,t.product_id limit 1 for update of t skip locked",
                    (rs,n)->new Task(rs.getLong(1),rs.getLong(2)));
                if(tasks.isEmpty()) return false;
                Task task=tasks.getFirst();selected[0]=task;
                jdbc.queryForList("select id from product where id=? for update",task.productId());
                var result=itemService.recalculate(task.productId());
                jdbc.update("update import_recalculation_task set status=?,attempts=attempts+1,last_error=null,finished_at=now() where batch_id=? and product_id=?",
                        result.name(),task.batchId(),task.productId());
                return true;
            }));
        } catch(RuntimeException error) {
            if(selected[0]==null) throw error;
            Task task=selected[0];
            log.error("FR09 重算失敗 batchId={}, productId={}",task.batchId(),task.productId(),error);
            transaction.executeWithoutResult(status -> jdbc.update("""
                update import_recalculation_task set attempts=attempts+1,
                  status=case when attempts+1>=3 then 'FAILED' else 'PENDING' end,
                  last_error='評分更新失敗，請查看伺服器日誌或重試',
                  next_attempt_at=now()+interval '30 seconds',
                  finished_at=case when attempts+1>=3 then now() else null end
                where batch_id=? and product_id=? and status='PENDING'
                """,task.batchId(),task.productId()));
            return true;
        }
    }
    private record Task(Long batchId,Long productId) {}
}
