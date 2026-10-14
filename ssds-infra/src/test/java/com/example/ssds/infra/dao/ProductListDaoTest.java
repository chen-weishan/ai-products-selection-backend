package com.example.ssds.infra.dao;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProductListDaoTest {

    @Test
    void latestScoreUsesOnlyActivePrimaryScene() {
        assertTrue(ProductListDao.FROM_SQL.contains("ps.is_primary = TRUE"));
        assertTrue(ProductListDao.FROM_SQL.contains("ps.is_active = TRUE"));
    }

    @Test
    void latestScorePrefersLatestPeriodBeforeCalculationTime() {
        assertTrue(ProductListDao.FROM_SQL.contains(
                "ORDER BY ps.period DESC, ps.calculated_at DESC, ps.id DESC"));
    }
}
