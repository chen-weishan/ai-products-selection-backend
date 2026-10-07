package com.example.ssds.infra.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssds.core.domain.SourcingStatus;
import com.example.ssds.infra.dao.projection.SourcingQueueRow;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises the real page query on an isolated database, without production migrations or data. */
@Testcontainers(disabledWithoutDocker = true)
class SourcingQueueDaoTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");

    static JdbcClient jdbc;
    static SourcingQueueDao dao;

    @BeforeAll
    static void seedQueue() {
        jdbc = JdbcClient.create(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        dao = new SourcingQueueDao(jdbc);
        jdbc.sql("CREATE TABLE product (id BIGINT PRIMARY KEY, name TEXT, track_type TEXT, "
                + "deleted_at TIMESTAMP, sourcing_status TEXT)").update();
        jdbc.sql("CREATE TABLE sourcing_candidate (product_id BIGINT, driving_keyword_id BIGINT, "
                + "lead_time_days INTEGER)").update();
        jdbc.sql("CREATE TABLE trend_keyword (id BIGINT PRIMARY KEY, enabled BOOLEAN)").update();
        jdbc.sql("CREATE TABLE heat_composite_daily (keyword_id BIGINT, stat_date DATE, stage TEXT, "
                + "stage_weeks SMALLINT, estimated_lifespan_days INTEGER)").update();

        seed(1, "PENDING", null);
        seed(2, "SOURCING", 20);
        seed(3, "URGENT", 10);
        seed(4, "REJECTED", -30);
        seed(5, "URGENT", 0);
        seed(6, "REJECTED", -1);
        seed(7, "URGENT", 10);
        seed(8, "PENDING", 15);
        seed(9, "REJECTED", null);
        seed(10, "URGENT", null);
        seed(11, "SOURCING", 30);
        seed(12, "URGENT", 0);
        seed(13, "URGENT", 0);
        jdbc.sql("UPDATE product SET track_type = 'A' WHERE id = 12").update();
        jdbc.sql("UPDATE product SET deleted_at = CURRENT_TIMESTAMP WHERE id = 13").update();
        // An older, conflicting heat row must not determine the displayed gap or priority.
        jdbc.sql("INSERT INTO heat_composite_daily VALUES (5, DATE '2026-10-05', 'RISING', 1, 64)")
                .update();
    }

    private static void seed(int id, String status, Integer gap) {
        jdbc.sql("INSERT INTO product VALUES (?, ?, 'B', NULL, ?)")
                .params(id, "candidate-" + id, status).update();
        jdbc.sql("INSERT INTO sourcing_candidate VALUES (?, ?, 50)").params(id, id).update();
        jdbc.sql("INSERT INTO trend_keyword VALUES (?, TRUE)").params(id).update();
        if (gap != null) {
            jdbc.sql("INSERT INTO heat_composite_daily VALUES (?, DATE '2026-10-06', 'RISING', 2, ?)")
                    .params(id, 50 + gap).update();
        }
    }

    @Test
    void groupsStatusesAndSortsUrgentAscendingAndRejectedDescendingWithMissingGapsLast() {
        assertEquals(List.of(5L, 3L, 7L, 10L, 2L, 11L, 8L, 1L, 6L, 4L, 9L),
                ids(dao.findPage(Set.of(), 0, 50)));
    }

    @Test
    void sortsBeforePaginationWithStableTiesAcrossPageBoundaries() {
        assertEquals(List.of(5L, 3L), ids(dao.findPage(Set.of(), 0, 2)));
        assertEquals(List.of(7L, 10L), ids(dao.findPage(Set.of(), 1, 2)));
        assertEquals(List.of(6L, 4L), ids(dao.findPage(Set.of(), 4, 2)));
        assertEquals(List.of(9L), ids(dao.findPage(Set.of(), 5, 2)));
    }

    @Test
    void filteredQueuesKeepTheSameWithinStatusOrder() {
        assertEquals(List.of(5L, 3L, 7L, 10L),
                ids(dao.findPage(Set.of(SourcingStatus.URGENT), 0, 50)));
        assertEquals(List.of(6L, 4L, 9L),
                ids(dao.findPage(Set.of(SourcingStatus.REJECTED), 0, 50)));
        assertEquals(3, dao.count(Set.of(SourcingStatus.REJECTED)).totalElements());
    }

    private static List<Long> ids(List<SourcingQueueRow> rows) {
        return rows.stream().map(SourcingQueueRow::productId).toList();
    }
}
