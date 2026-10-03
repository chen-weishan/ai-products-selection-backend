package com.example.ssds.ingest.importer;

import com.example.ssds.core.domain.ImportDataType;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 依系統欄位名稱、中文標籤與常見別名自動猜測檔案欄位。 */
@Component
public class ImportHeaderMapper {

    private final ImportFieldRegistry registry;

    public ImportHeaderMapper(ImportFieldRegistry registry) {
        this.registry = registry;
    }

    public List<ImportColumnSuggestion> suggest(
            ImportDataType dataType,
            List<String> headers
    ) {
        Map<String, ImportSystemField> aliases = new HashMap<>();
        for (ImportSystemField field : registry.fieldsFor(dataType)) {
            for (String alias : field.aliases()) {
                aliases.putIfAbsent(normalize(alias), field);
            }
        }

        return headers.stream().map(header -> {
            String normalized = normalize(header);
            if (registry.isPersonalDataHeader(dataType, normalized)) {
                return new ImportColumnSuggestion(
                        header, null, ImportMappingStatus.BLOCKED_PERSONAL_DATA, 100);
            }
            ImportSystemField field = aliases.get(normalized);
            if (field == null) {
                return new ImportColumnSuggestion(
                        header, null, ImportMappingStatus.UNMAPPED, 0);
            }
            int confidence = normalized.equals(normalize(field.key())) ? 100 : 95;
            return new ImportColumnSuggestion(
                    header, field.key(), ImportMappingStatus.AUTO_MAPPED, confidence);
        }).toList();
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace("\uFEFF", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s_\\-./()（）]+", "")
                .trim();
    }
}
