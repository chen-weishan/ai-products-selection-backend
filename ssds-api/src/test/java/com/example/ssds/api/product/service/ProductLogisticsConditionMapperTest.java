package com.example.ssds.api.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssds.core.domain.LogisticsCondition;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductLogisticsConditionMapperTest {

    @Test
    void encodesConditionsAsStableCodes() {
        assertEquals(
                "NORMAL,FRAGILE",
                ProductLogisticsConditionMapper.encode(Set.of(
                        LogisticsCondition.FRAGILE,
                        LogisticsCondition.NORMAL
                ))
        );
    }

    @Test
    void decodesLegacyChineseDescriptions() {
        assertEquals(
                Set.of(LogisticsCondition.NORMAL, LogisticsCondition.MELTABLE),
                ProductLogisticsConditionMapper.decode("常溫｜夏季易融化")
        );
    }
}
