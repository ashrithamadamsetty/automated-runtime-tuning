package com.opentext.automatedruntimetuning.controller;

import com.opentext.automatedruntimetuning.actuator.DatabasePoolActuator;
import com.opentext.automatedruntimetuning.actuator.RateLimiterActuator;
import com.opentext.automatedruntimetuning.actuator.ThreadPoolActuator;
import com.opentext.automatedruntimetuning.audit.AuditEntry;
import com.opentext.automatedruntimetuning.audit.AuditLog;
import com.opentext.automatedruntimetuning.config.TuningProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Owns the independent control loops and coordinates them so that they do not work
 * against each other.
 *
 * <p>The thread pool and the connection pool are coupled: growing the thread pool while
 * threads are blocked waiting for a database connection increases contention without
 * increasing throughput. When that condition is detected the thread pool loop is held
 * and inbound load is shed instead.
 */
@Component
public class ControlLoop {

    public static final String THREAD_POOL = "thread-pool";
    public static final String DATABASE_POOL = "database-pool";
    public static final String RATE_LIMITER = "rate-limiter";

    private static final Logger log = LoggerFactory.getLogger(ControlLoop.class);

    private final TuningProperties properties;
    private final TuningState state;
    private final AuditLog audit;
    private final DatabasePoolActuator databasePoolActuator;
    private final RateLimiterActuator rateLimiterActuator;

    private final Map<String, TuningLoop> loops = new LinkedHashMap<>();
    private final AtomicInteger downstreamStarved = new AtomicInteger();

    public ControlLoop(TuningProperties properties,
                       TuningState state,
                       ThreadPoolActuator threadPoolActuator,
                       DatabasePoolActuator databasePoolActuator,
                       RateLimiterActuator rateLimiterActuator,
                       SafetyManager safetyManager,
                       ControlLoopMetrics metrics,
                       AuditLog audit,
                       MeterRegistry registry) {
        this.properties = properties;
        this.state = state;
        this.audit = audit;
        this.databasePoolActuator = databasePoolActuator;
        this.rateLimiterActuator = rateLimiterActuator;

        state.initialiseMode(properties.isEnabled() ? TuningMode.ACTIVE : TuningMode.SHADOW);

        loops.put(THREAD_POOL, new TuningLoop(
                THREAD_POOL,
                properties.getThreadPool(),
                threadPoolActuator::utilizationPercent,
                threadPoolActuator::currentPoolSize,
                threadPoolActuator::updatePoolSize,
                safetyManager, metrics));

        loops.put(DATABASE_POOL, new TuningLoop(
                DATABASE_POOL,
                properties.getDatabasePool(),
                databasePoolActuator::utilizationPercent,
                databasePoolActuator::maxPoolSize,
                databasePoolActuator::updatePoolSize,
                safetyManager, metrics));

        loops.put(RATE_LIMITER, new TuningLoop(
                RATE_LIMITER,
                properties.getRateLimiter(),
                threadPoolActuator::utilizationPercent,
                rateLimiterActuator::currentLimit,
                rateLimiterActuator::updateLimit,
                safetyManager, metrics));

        Gauge.builder("tuning.downstream.starved", downstreamStarved, AtomicInteger::get)
                .description("1 when threads are blocked waiting for database connections")
                .register(registry);
    }

    public Map<String, TuningLoop> loops() {
        return Map.copyOf(loops);
    }

    public TuningLoop loop(String name) {
        TuningLoop loop = loops.get(name);
        if (loop == null) {
            throw new IllegalArgumentException("Unknown control loop: " + name);
        }
        return loop;
    }

    /**
     * Evaluates every loop once. In SHADOW the loops still run so that observability is
     * continuous; only the final write is gated. In OFF nothing runs at all.
     */
    public synchronized Map<String, ControlDecision> evaluateAll() {
        if (state.getMode() == TuningMode.OFF) {
            return Map.of();
        }

        boolean starved = detectDownstreamStarvation();
        downstreamStarved.set(starved ? 1 : 0);

        Map<String, ControlDecision> decisions = new LinkedHashMap<>();
        for (Map.Entry<String, TuningLoop> entry : loops.entrySet()) {
            TuningLoop loop = entry.getValue();
            ControlDecision decision = loop.evaluate(mayApply(loop, starved));
            decisions.put(entry.getKey(), decision);
            recordDecision(decision);
        }

        if (starved) {
            shedLoad();
        }
        return decisions;
    }

    /** Restores every resource to its configured default and clears controller state. */
    public synchronized void resetAll() {
        log.info("Resetting all control loops to configured defaults");
        loops.values().forEach(loop -> {
            loop.reset();
            audit.record(new AuditEntry(Instant.now(), loop.name(), state.getMode().wireName(),
                    null, null, loop.config().getDefaultValue(), true, "reset to default"));
        });
    }

    private boolean mayApply(TuningLoop loop, boolean starved) {
        if (state.getMode() != TuningMode.ACTIVE || state.isEmergencyStopped()) {
            return false;
        }
        if (!loop.config().isEnabled()) {
            return false;
        }
        // Adding request threads cannot help when the bottleneck is downstream.
        return !(starved && THREAD_POOL.equals(loop.name()));
    }

    /**
     * Only decisions that explain a change, or the absence of one that was wanted, are
     * audited. Recording every quiet cycle would bury the interesting lines.
     */
    private void recordDecision(ControlDecision d) {
        String mode = state.getMode().wireName();

        if (d.applied()) {
            audit.record(new AuditEntry(Instant.now(), d.loop(), mode,
                    d.currentValue(), d.requestedValue(), d.appliedValue(), true,
                    String.format("%s loop: measured %.1f vs target %.1f", d.loop(), d.actual(), d.target())));
            return;
        }

        String reason = d.reason();
        if (reason == null) {
            return;
        }
        if ("shadow mode".equals(reason) && d.requestedValue() != d.currentValue()) {
            audit.record(new AuditEntry(Instant.now(), d.loop(), mode,
                    d.currentValue(), d.requestedValue(), d.currentValue(), false,
                    String.format("shadow mode: would have set %d (measured %.1f vs target %.1f)",
                            d.requestedValue(), d.actual(), d.target())));
        } else if (reason.startsWith("measurement failed")
                || reason.startsWith("apply failed")
                || reason.startsWith("non-finite")) {
            audit.record(new AuditEntry(Instant.now(), d.loop(), mode,
                    d.currentValue(), d.requestedValue(), d.currentValue(), false, reason));
        }
    }

    private boolean detectDownstreamStarvation() {
        TuningProperties.Backpressure backpressure = properties.getBackpressure();
        if (!backpressure.isEnabled()) {
            return false;
        }
        try {
            return databasePoolActuator.pendingConnections() >= backpressure.getPendingConnectionThreshold();
        } catch (RuntimeException e) {
            log.warn("Unable to evaluate downstream starvation: {}", e.toString());
            return false;
        }
    }

    /**
     * Reduces admitted traffic when the application is blocked on the database, rather
     * than continuing to add capacity that cannot be used.
     */
    private void shedLoad() {
        if (state.getMode() != TuningMode.ACTIVE || state.isEmergencyStopped()) {
            log.debug("Downstream starvation detected but tuning is not active; no load shed");
            return;
        }
        TuningProperties.Loop config = properties.getRateLimiter();
        if (!config.isEnabled()) {
            return;
        }
        int current = rateLimiterActuator.currentLimit();
        int reduced = Math.max(config.getMin(),
                (int) Math.round(current * properties.getBackpressure().getShedFactor()));
        if (reduced >= current) {
            return;
        }
        try {
            rateLimiterActuator.updateLimit(reduced);
            log.warn("Threads are waiting for database connections; reducing rate limit {} -> {}",
                    current, reduced);
            audit.record(new AuditEntry(Instant.now(), RATE_LIMITER, state.getMode().wireName(),
                    current, reduced, reduced, true,
                    "backpressure: threads waiting on database connections, load shed"));
        } catch (RuntimeException e) {
            log.error("Failed to shed load by reducing the rate limit", e);
        }
    }
}