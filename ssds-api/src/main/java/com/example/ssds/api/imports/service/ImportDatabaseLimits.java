package com.example.ssds.api.imports.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Transaction-local PostgreSQL limits also bound JDBC calls and row-lock waits. */
@Component
public class ImportDatabaseLimits {
    private final JdbcTemplate jdbc;
    private final int seconds;
    public ImportDatabaseLimits(JdbcTemplate jdbc,
            @Value("${ssds.import.db-timeout-seconds:30}") int seconds) {
        if (seconds <= 0) throw new IllegalArgumentException("Import database timeout must be positive");
        this.jdbc = jdbc;
        this.seconds = seconds;
    }
    public int seconds() { return seconds; }
    public void apply() {
        String limit = seconds + "s";
        jdbc.queryForObject("select set_config('statement_timeout', ?, true)", String.class, limit);
        jdbc.queryForObject("select set_config('lock_timeout', ?, true)", String.class, limit);
    }
}
