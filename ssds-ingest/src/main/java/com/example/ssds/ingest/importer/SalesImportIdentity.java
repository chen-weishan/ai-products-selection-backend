package com.example.ssds.ingest.importer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Stable business identifiers are distinct from values that can be corrected. */
public final class SalesImportIdentity {
    private SalesImportIdentity() {}
    public static String key(Map<String,String> v, Long productId) {
        String source = value(v,"sourceSystem");
        if (source.isEmpty()) return null; // Anonymous transactions are not content-deduplicated.
        if ("SUMMARY".equalsIgnoreCase(value(v,"salesKind"))) {
            return hash(List.of("SUMMARY",source,value(v,"orderDate"),
                    productId == null ? value(v,"category")+":"+value(v,"productName") : "id:"+productId,
                    value(v,"channel"),value(v,"summaryDimension"),value(v,"audienceCode")));
        }
        if (value(v,"orderNo").isEmpty() || value(v,"lineNo").isEmpty()) return null;
        return hash(List.of("DETAIL",source,value(v,"orderNo"),value(v,"lineNo")));
    }
    public static String payload(Map<String,String> v, Long productId) {
        var canonical = new TreeMap<String,String>();
        for (String key : List.of("orderDate","productName","category","price","qty","impression",
                "audienceCode","channel","summaryDimension")) {
            String value=value(v,key);
            if (List.of("price","qty","impression").contains(key) && !value.isEmpty())
                value=new BigDecimal(value).stripTrailingZeros().toPlainString();
            canonical.put(key,value);
        }
        canonical.put("productId",productId == null ? "" : productId.toString());
        return hash(canonical.entrySet().stream().map(e -> e.getKey()+"="+e.getValue()).toList());
    }
    private static String value(Map<String,String> v,String key) { return v.getOrDefault(key,"").trim(); }
    public static String hash(List<String> values) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(String v:values) {
                byte[] bytes=v.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
