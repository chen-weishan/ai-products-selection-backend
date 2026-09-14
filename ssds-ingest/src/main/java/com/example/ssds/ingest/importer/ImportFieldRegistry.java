package com.example.ssds.ingest.importer;

import com.example.ssds.core.domain.ImportDataType;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * FR-09 四種匯入資料的欄位單一事實來源。
 *
 * <p>AUDIENCE 只接受去識別化的客群統計；會員 ID、電話、Email、地址與訂單編號
 * 會由 {@link ImportHeaderMapper} 標成禁止匯入，不會被誤猜到一般文字欄位。
 */
@Component
public class ImportFieldRegistry {

    private static final Map<ImportDataType, List<ImportSystemField>> FIELDS = fields();

    private static final Set<String> PERSONAL_DATA_HEADERS = Set.of(
            "會員id", "會員編號", "memberid", "userid", "使用者id", "帳號",
            "電話", "手機", "mobile", "phone", "email", "電子郵件",
            "地址", "address", "訂單編號", "orderno", "orderid"
    );

    public List<ImportSystemField> fieldsFor(ImportDataType dataType) {
        List<ImportSystemField> fields = FIELDS.get(dataType);
        if (fields == null) {
            throw new IllegalArgumentException("不支援的匯入資料類型：" + dataType);
        }
        return fields;
    }

    public boolean isPersonalDataHeader(String normalizedHeader) {
        return PERSONAL_DATA_HEADERS.contains(normalizedHeader);
    }

    private static Map<ImportDataType, List<ImportSystemField>> fields() {
        Map<ImportDataType, List<ImportSystemField>> fields = new EnumMap<>(ImportDataType.class);
        fields.put(ImportDataType.SALES, List.of(
                field("orderDate", "訂單日期", ImportValueType.DATE, true,
                        "order_date", "saleDate", "交易日期", "日期"),
                field("productId", "品項 ID", ImportValueType.LONG, false,
                        "product_id", "商品ID", "品項編號"),
                field("productName", "品名", ImportValueType.STRING, true,
                        "product_name", "商品名稱", "品項名稱", "商品"),
                field("category", "類別", ImportValueType.STRING, false,
                        "categoryName", "category_name", "大類", "品類"),
                field("price", "單價", ImportValueType.DECIMAL, true,
                        "unitPrice", "unit_price", "售價", "成交價"),
                field("qty", "數量", ImportValueType.INTEGER, true,
                        "quantity", "銷量", "件數"),
                field("impression", "瀏覽數", ImportValueType.INTEGER, false,
                        "impressions", "曝光數", "瀏覽量"),
                field("audienceCode", "客群代碼", ImportValueType.STRING, false,
                        "audience_code", "客群", "客群區隔")
        ));
        fields.put(ImportDataType.REVIEW, List.of(
                field("productId", "品項 ID", ImportValueType.LONG, false,
                        "product_id", "商品ID", "品項編號"),
                field("productName", "品名", ImportValueType.STRING, true,
                        "product_name", "商品名稱", "品項名稱", "商品"),
                field("source", "來源", ImportValueType.STRING, false,
                        "platform", "來源平台"),
                field("content", "評論內容", ImportValueType.STRING, true,
                        "review", "comment", "評論", "內容"),
                field("rating", "評分", ImportValueType.DECIMAL, false,
                        "stars", "星等", "星級"),
                field("reviewedAt", "評論日期", ImportValueType.DATE, false,
                        "reviewed_at", "reviewDate", "留言日期")
        ));
        fields.put(ImportDataType.AUDIENCE, List.of(
                field("audienceCode", "客群代碼", ImportValueType.STRING, true,
                        "audience_code", "segmentCode", "區隔代碼"),
                field("name", "客群名稱", ImportValueType.STRING, true,
                        "audienceName", "segmentName", "區隔名稱"),
                field("priceMin", "價格帶下限", ImportValueType.DECIMAL, true,
                        "price_min", "最低價格", "最低消費"),
                field("priceMax", "價格帶上限", ImportValueType.DECIMAL, true,
                        "price_max", "最高價格", "最高消費"),
                field("note", "備註", ImportValueType.STRING, false,
                        "description", "說明"),
                field("category", "類別", ImportValueType.STRING, false,
                        "categoryName", "category_name", "品類"),
                field("share", "客群佔比", ImportValueType.DECIMAL, false,
                        "ratio", "percentage", "佔比")
        ));
        fields.put(ImportDataType.PRODUCT, List.of(
                field("name", "品項名稱", ImportValueType.STRING, true,
                        "productName", "product_name", "品名", "商品名稱"),
                field("category", "類別", ImportValueType.STRING, true,
                        "categoryName", "category_name", "大類", "品類"),
                field("trackType", "軌別", ImportValueType.ENUM, false,
                        "track_type", "track", "AB軌"),
                field("supplier", "供應商", ImportValueType.STRING, false,
                        "supplierName", "supplier_name", "廠商"),
                field("cost", "成本", ImportValueType.DECIMAL, false,
                        "purchasePrice", "進貨價"),
                field("suggestedPrice", "建議售價", ImportValueType.DECIMAL, false,
                        "suggested_price", "salePrice", "售價"),
                field("moq", "最低訂購量", ImportValueType.INTEGER, false,
                        "minimumOrderQuantity", "最小訂購量"),
                field("season", "季節", ImportValueType.ENUM, false,
                        "季節性"),
                field("logisticsCondition", "物流條件", ImportValueType.STRING, false,
                        "logistics_condition", "物流"),
                field("idealTempMin", "適溫下限", ImportValueType.DECIMAL, false,
                        "ideal_temp_min", "最低適溫"),
                field("idealTempMax", "適溫上限", ImportValueType.DECIMAL, false,
                        "ideal_temp_max", "最高適溫"),
                field("shelfLifeDays", "效期天數", ImportValueType.INTEGER, false,
                        "shelf_life_days", "保存天數", "效期")
        ));
        return Map.copyOf(fields);
    }

    private static ImportSystemField field(
            String key,
            String label,
            ImportValueType type,
            boolean required,
            String... aliases
    ) {
        Set<String> allAliases = new LinkedHashSet<>();
        allAliases.add(key);
        allAliases.add(label);
        allAliases.addAll(List.of(aliases));
        return new ImportSystemField(key, label, type, required, allAliases);
    }
}
