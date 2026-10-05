package com.opentext.automatedruntimetuning.actuator;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads and modifies the HikariCP connection pool at runtime.
 *
 * <p>The maximum pool size is additionally constrained by an absolute ceiling that
 * represents the connection budget granted by the database administrator. No controller
 * output is permitted to exceed it.
 */
@Component
public class DatabasePoolActuator {

    private final HikariDataSource dataSource;
    private final int dbaMaximumConnections;

    public DatabasePoolActuator(HikariDataSource dataSource,
                                MeterRegistry registry,
                                @Value("${tuning.database-pool.dba-max-connections:20}") int dbaMaximumConnections) {
        this.dataSource = dataSource;
        this.dbaMaximumConnections = dbaMaximumConnections;
        registerMetrics(registry);
    }

    /** Connections currently held by the pool, busy or idle. */
    public int currentPoolSize() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        return pool != null ? pool.getTotalConnections() : 0;
    }

    /** Configured upper bound on pool size. */
    public int maxPoolSize() {
        return dataSource.getMaximumPoolSize();
    }

    public int activeConnections() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        return pool != null ? pool.getActiveConnections() : 0;
    }

    public int idleConnections() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        return pool != null ? pool.getIdleConnections() : 0;
    }

    /** Threads blocked waiting for a connection to become available. */
    public int pendingConnections() {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        return pool != null ? pool.getThreadsAwaitingConnection() : 0;
    }

    /** Percentage of the configured maximum pool size currently in use. */
    public int utilizationPercent() {
        int max = maxPoolSize();
        if (max <= 0) {
            return 0;
        }
        return (int) Math.round((activeConnections() * 100.0) / max);
    }

    /** The absolute ceiling agreed with the database administrator. */
    public int dbaMaximumConnections() {
        return dbaMaximumConnections;
    }

    public void updatePoolSize(int newSize) {
        if (newSize <= 0) {
            throw new IllegalArgumentException("Database pool size must be greater than zero");
        }
        if (newSize > dbaMaximumConnections) {
            throw new IllegalArgumentException(
                    "Requested pool size " + newSize + " exceeds the DBA limit of " + dbaMaximumConnections);
        }
        dataSource.setMaximumPoolSize(newSize);
    }

    private void registerMetrics(MeterRegistry registry) {
        Gauge.builder("apptuning.dbpool.max", this, DatabasePoolActuator::maxPoolSize)
                .description("Configured maximum database connections")
                .register(registry);
        Gauge.builder("apptuning.dbpool.active", this, DatabasePoolActuator::activeConnections)
                .description("Database connections currently in use")
                .register(registry);
        Gauge.builder("apptuning.dbpool.idle", this, DatabasePoolActuator::idleConnections)
                .description("Idle database connections")
                .register(registry);
        Gauge.builder("apptuning.dbpool.pending", this, DatabasePoolActuator::pendingConnections)
                .description("Threads waiting for a database connection")
                .register(registry);
        Gauge.builder("apptuning.dbpool.dba.limit", this, DatabasePoolActuator::dbaMaximumConnections)
                .description("Absolute connection ceiling approved by the DBA")
                .register(registry);
        Gauge.builder("apptuning.dbpool.utilization", this, DatabasePoolActuator::utilizationPercent)
                .description("Percentage of maximum database connections in use")
                .register(registry);
    }
}
