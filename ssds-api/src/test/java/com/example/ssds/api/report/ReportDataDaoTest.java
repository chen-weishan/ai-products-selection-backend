package com.example.ssds.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ssds.api.report.service.ReportDataDao;
import com.example.ssds.core.domain.ReportType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class ReportDataDaoTest {

    @Test
    void weeklyReportUsesOnlyReadableRecommendationInsights() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        JdbcClient.ResultQuerySpec result = mock(JdbcClient.ResultQuerySpec.class);
        List<String> sqlStatements = new ArrayList<>();

        when(jdbc.sql(anyString())).thenAnswer(invocation -> {
            sqlStatements.add(invocation.getArgument(0));
            return statement;
        });
        when(statement.param(anyString(), nullable(Object.class))).thenReturn(statement);
        when(statement.query()).thenReturn(result);
        when(result.listOfRows()).thenReturn(List.of());

        new ReportDataDao(jdbc).load(
                ReportType.WEEKLY_PICK,
                Map.of("period", "2026W40", "categoryId", 3L));

        assertEquals(5, sqlStatements.size());
        for (String sql : sqlStatements.subList(0, 4)) {
            assertTrue(sql.contains("i.insight_type = 'RECOMMENDATION'"));
            assertTrue(sql.contains("ai.content_json ->> 'action'"));
            assertTrue(sql.contains("ai.content_json ->> 'reasoning'"));
            assertFalse(sql.contains("ai.content_json::text"));
            assertFalse(sql.contains("content_json ?"));
        }
    }
}
