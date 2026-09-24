package com.example.ssds.api.imports.service;

import com.example.ssds.api.imports.dto.ImportPreviewIssue;
import com.example.ssds.core.domain.ImportDataType;
import java.math.BigDecimal;
import java.util.*;

/** First pass retains only group totals and master definitions, never every source row. */
final class AudienceImportPlan {
    private final ImportPreviewService validator;
    private final ImportPreviewService.ReferenceCatalog references;
    private final Map<String,Map<String,BigDecimal>> proposed=new TreeMap<>();
    private final Map<String,String> categoryNames=new HashMap<>();
    private final Map<String,BigDecimal> totals=new HashMap<>();
    private final Map<String,String> errors=new HashMap<>();
    private final Map<String,String> definitions=new HashMap<>();
    private final Map<String,Set<String>> groupsByCode=new HashMap<>();
    private final Set<String> conflictingCodes=new HashSet<>();
    private final Set<String> relations=new HashSet<>();
    AudienceImportPlan(ImportPreviewService validator,ImportPreviewService.ReferenceCatalog references) {
        this.validator=validator;this.references=references;
    }
    private String group(Map<String,String> v) {
        return validator.blank(v.get("category")) ? "master:"+validator.key(v.get("audienceCode")) : "category:"+validator.key(v.get("category"));
    }
    void accept(Map<String,String> v) {
        String group=group(v),code=validator.key(v.get("audienceCode"));
        groupsByCode.computeIfAbsent(code,k->new HashSet<>()).add(group);
        var issues=validator.validateRow(ImportDataType.AUDIENCE,v,references);
        if(!issues.isEmpty()) errors.put(group,"同品類或客群主檔有錯誤，整組不寫入；請修正後整組重傳");
        var definition=new ArrayList<String>();
        for(String field:List.of("name","priceMin","priceMax","note")) {
            String value=v.getOrDefault(field,"");
            if(field.startsWith("price")) try {value=new BigDecimal(value).stripTrailingZeros().toPlainString();} catch(NumberFormatException ignored) {}
            definition.add(value);
        }
        String hash=com.example.ssds.ingest.importer.SalesImportIdentity.hash(definition);
        String previous=definitions.putIfAbsent(code,hash);
        if(previous!=null && !previous.equals(hash)) conflictingCodes.add(code);
        if(!relations.add(group+":"+code)) errors.put(group,"同品類的客群重複（或客群主檔重複），整組不寫入");
        if(!validator.blank(v.get("category"))) {
            totals.putIfAbsent(group,BigDecimal.ZERO);
            categoryNames.put(group,v.get("category"));
            var share=validator.decimal(v.get("share"));
            if(share!=null) proposed.computeIfAbsent(group,k->new TreeMap<>()).put(v.get("audienceCode"),share);
            try {totals.merge(group,new BigDecimal(v.getOrDefault("share","")),BigDecimal::add);} catch(NumberFormatException ignored) {}
        }
    }
    void finish() {
        totals.forEach((group,sum)->{if(sum.compareTo(BigDecimal.ONE)!=0)
            errors.put(group,"同品類客群占比合計為 "+sum.toPlainString()+"，必須為 1.000；請提供完整組成");});
        for(String code:conflictingCodes) for(String group:groupsByCode.get(code))
            errors.put(group,"同一客群代碼的主檔內容互相矛盾，相關品類整組不寫入");
    }
    List<com.example.ssds.api.imports.dto.ImportPreviewResponse.AudienceChange> changes(com.example.ssds.infra.dao.ImportIntegrityDao dao) {
        var result=new ArrayList<com.example.ssds.api.imports.dto.ImportPreviewResponse.AudienceChange>();
        proposed.forEach((group,after)->{
            if(result.size()>=20 || errors.containsKey(group)) return;
            String name=categoryNames.get(group);
            var categories=references.categories().get(validator.key(name));
            if(categories!=null && categories.size()==1) result.add(new com.example.ssds.api.imports.dto.ImportPreviewResponse.AudienceChange(
                name,dao.audienceMix(categories.getFirst().getId()),Map.copyOf(after)));
        });
        return List.copyOf(result);
    }
    void addIssue(Map<String,String> values,List<ImportPreviewIssue> issues) {
        String message=errors.get(group(values));
        if(message!=null) issues.add(ImportPreviewIssue.error("category",message));
    }
}
