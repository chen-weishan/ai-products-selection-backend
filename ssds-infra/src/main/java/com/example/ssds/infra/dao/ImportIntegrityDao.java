package com.example.ssds.infra.dao;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ImportIntegrityDao {
    private final JdbcTemplate jdbc;
    public ImportIntegrityDao(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public String existingPayload(String key) {
        if(key==null) return null;
        return jdbc.query("select payload_hash from import_sales_identity where identity_key=?",
                (rs,n)->rs.getString(1),key).stream().findFirst().orElse(null);
    }
    public Map<String,String> existingPayloads(Collection<String> keys) {
        if(keys.isEmpty()) return Map.of();
        var result=new HashMap<String,String>();
        String placeholders=String.join(",",Collections.nCopies(keys.size(),"?"));
        jdbc.query("select identity_key,payload_hash from import_sales_identity where identity_key in ("+placeholders+")",
                rs->{result.put(rs.getString(1),rs.getString(2));},keys.toArray());
        return result;
    }
    public void reserveSales(Long batchId,Map<String,String> identities) {
        if(identities.isEmpty()) return;
        var entries=new ArrayList<>(identities.entrySet());
        int[][] counts=jdbc.batchUpdate("insert into import_sales_identity(identity_key,payload_hash,batch_id) values(?,?,?) on conflict do nothing",
                entries,500,(ps,e)->{ps.setString(1,e.getKey());ps.setString(2,e.getValue());ps.setLong(3,batchId);});
        int index=0;
        for(int[] batch:counts) for(int count:batch) {
            var entry=entries.get(index++);
            if(count==0) {
                if(Objects.equals(existingPayload(entry.getKey()),entry.getValue())) throw new DuplicateSaleException();
                throw new ConflictingSaleException();
            }
        }
    }
    /** Called inside the same transaction as the sales insert. Unique key resolves races. */
    public void reserveSale(Long batchId,String key,String payload) {
        if(key==null) return;
        if(jdbc.update("insert into import_sales_identity(identity_key,payload_hash,batch_id) values(?,?,?) on conflict do nothing",
                key,payload,batchId)==0) {
            if(Objects.equals(existingPayload(key),payload)) throw new DuplicateSaleException();
            throw new ConflictingSaleException();
        }
    }
    public void claimFile(Long batchId,String fingerprint) {
        jdbc.update("delete from import_file_claim c using import_batch b where c.batch_id=b.id and c.fingerprint=? and b.status in ('FAILED','CANCELLED') and b.success_rows=0",fingerprint);
        if(jdbc.update("insert into import_file_claim(fingerprint,batch_id) values(?,?) on conflict do nothing",fingerprint,batchId)==0)
            throw new IllegalArgumentException("相同銷售檔案與欄位對應已確認匯入；請查看原批次，僅重傳待修正或未處理資料");
    }
    public void enqueue(Long batchId,Collection<Long> productIds) {
        var ids=productIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if(!ids.isEmpty()) jdbc.batchUpdate("insert into import_recalculation_task(batch_id,product_id) values(?,?) on conflict do nothing",
                ids,500,(ps,id)->{ps.setLong(1,batchId);ps.setLong(2,id);});
    }
    public void enqueueAudience(Long batchId,Collection<String> codes,Collection<Long> categories) {
        var ids=new HashSet<Long>();
        for(String code:codes) ids.addAll(jdbc.query("select p.id from product p join category_audience_mix m on p.category_id=m.category_id join audience_segment a on a.id=m.audience_id where a.audience_code=? and p.deleted_at is null",(rs,n)->rs.getLong(1),code));
        for(Long category:categories) ids.addAll(jdbc.query("select id from product where category_id=? and deleted_at is null",(rs,n)->rs.getLong(1),category));
        enqueue(batchId,ids);
    }
    public void lockAudienceImport() { jdbc.queryForList("select pg_advisory_xact_lock(904000001)"); }
    public Map<String,java.math.BigDecimal> audienceMix(Long categoryId) {
        var result=new TreeMap<String,java.math.BigDecimal>();
        jdbc.query("select a.audience_code,m.share from category_audience_mix m join audience_segment a on a.id=m.audience_id where m.category_id=?",
            rs->{result.put(rs.getString(1),rs.getBigDecimal(2));},categoryId);
        return result;
    }
    public void clearMix(Long categoryId) {
        jdbc.queryForList("select id from category where id=? for update",categoryId);
        jdbc.update("delete from category_audience_mix where category_id=?",categoryId);
    }
    public Map<String,Integer> recalculationSummary(Long batchId) {
        var result=new LinkedHashMap<String,Integer>();
        jdbc.query("select status,count(*) as n from import_recalculation_task where batch_id=? group by status",rs->{result.put(rs.getString("status"),rs.getInt("n"));},batchId);
        return result;
    }
    public int retry(Long batchId) {
        return jdbc.update("update import_recalculation_task set status='PENDING',attempts=0,last_error=null,next_attempt_at=now(),finished_at=null where batch_id=? and status='FAILED'",batchId);
    }
    public static class DuplicateSaleException extends org.springframework.dao.DataIntegrityViolationException {
        public DuplicateSaleException(){super("相同來源識別與內容已存在，略過重複列");}
    }
    public static class ConflictingSaleException extends org.springframework.dao.DataIntegrityViolationException {
        public ConflictingSaleException(){super("相同來源識別已有不同內容；請核對原始資料，不會自動覆蓋");}
    }
}
