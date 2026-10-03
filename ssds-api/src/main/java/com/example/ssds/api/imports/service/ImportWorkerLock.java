package com.example.ssds.api.imports.service;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** PostgreSQL session lock held for the complete import, across chunk commits. */
@Component
public class ImportWorkerLock {
    private final DataSource dataSource;
    private final int queryTimeout;

    public ImportWorkerLock(DataSource dataSource,
            @Value("${ssds.import.db-timeout-seconds:30}") int queryTimeout) {
        this.dataSource = dataSource;
        if (queryTimeout < 1) throw new IllegalArgumentException("Import DB timeout must be positive");
        this.queryTimeout = queryTimeout;
    }

    public Lease tryAcquire(Long batchId) throws SQLException {
        if (batchId == null || batchId <= 0) throw new IllegalArgumentException("Invalid batch id");
        Connection connection = dataSource.getConnection();
        try {
            try (var statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
                statement.setQueryTimeout(queryTimeout);
                // Negative bigint namespace reserved for FR-09; full BIGINT batch IDs supported.
                statement.setLong(1, -batchId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (result.getBoolean(1)) return new Lease(connection, -batchId, queryTimeout);
                }
            }
            connection.close();
            return null;
        } catch (SQLException error) {
            try { connection.abort(Runnable::run); } catch (SQLException ignored) { }
            connection.close();
            throw error;
        }
    }

    public static final class Lease implements AutoCloseable {
        private final Connection connection;
        private final long key;
        private final int timeout;
        Lease(Connection connection, long key, int timeout) {
            this.connection = connection;
            this.key = key;
            this.timeout = timeout;
        }
        public void check() throws SQLException {
            if (!connection.isValid(timeout)) throw new SQLException("Import worker lock connection lost");
        }
        @Override
        public void close() throws SQLException {
            try (var statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
                statement.setQueryTimeout(timeout);
                statement.setLong(1, key);
                statement.execute();
            } catch (SQLException error) {
                // Never return a possibly locked physical session to the pool.
                connection.abort(Runnable::run);
                throw error;
            } finally {
                connection.close();
            }
        }
    }
}
