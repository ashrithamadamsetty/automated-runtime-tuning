package com.opentext.automatedruntimetuning.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.opentext.automatedruntimetuning.actuator.DatabasePoolActuator;
import com.opentext.automatedruntimetuning.actuator.RateLimiterActuator;
import com.opentext.automatedruntimetuning.actuator.ThreadPoolActuator;
import com.opentext.automatedruntimetuning.config.TuningProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the coordination rule from the requirements: when threads are blocked waiting
 * for database connections, capacity must not be added; inbound load must be reduced.
 */
class ControlLoopBackpressureTest {

    private TuningProperties properties;
    private TuningState state;
    private ThreadPoolActuator threadPool;
    private DatabasePoolActuator databasePool;
    private RateLimiterActuator rateLimiter;

    private final AtomicInteger threadPoolSize = new AtomicInteger(100);
    private final AtomicInteger rateLimit = new AtomicInteger(1000);

    @BeforeEach
    void setUp() {
        properties = new TuningProperties();
        properties.setEnabled(true);
        configureLoop(properties.getThreadPool(), true, 70, true);
        configureLoop(properties.getDatabasePool(), true, 70, true);
        configureLoop(properties.getRateLimiter(), true, 70, false);
        properties.getBackpressure().setEnabled(true);
        properties.getBackpressure().setPendingConnectionThreshold(1);
        properties.getBackpressure().setShedFactor(0.5);

        state = new TuningState();

        threadPool = mock(ThreadPoolActuator.class);
        databasePool = mock(DatabasePoolActuator.class);
        rateLimiter = mock(RateLimiterActuator.class);

        // A fully saturated thread pool: without backpressure the loop would grow it.
        when(threadPool.utilizationPercent()).thenReturn(100);
        when(threadPool.currentPoolSize()).thenAnswer(i -> threadPoolSize.get());
        doAnswer(i -> {
            threadPoolSize.set(i.getArgument(0));
            return null;
        }).when(threadPool).updatePoolSize(anyInt());

        when(databasePool.utilizationPercent()).thenReturn(100);
        when(databasePool.maxPoolSize()).thenReturn(10);

        when(rateLimiter.currentLimit()).thenAnswer(i -> rateLimit.get());
        doAnswer(i -> {
            rateLimit.set(i.getArgument(0));
            return null;
        }).when(rateLimiter).updateLimit(anyInt());
    }

    private static void configureLoop(TuningProperties.Loop loop,
                                      boolean enabled,
                                      double target,
                                      boolean reverseActing) {
        loop.setEnabled(enabled);
        loop.setTarget(target);
        loop.setReverseActing(reverseActing);
        loop.setKp(0.5);
        loop.setKi(0);
        loop.setKd(0);
        loop.setMin(2);
        loop.setMax(5000);
        loop.setMaxStep(100);
        loop.setDeadband(1);
        loop.setCooldown(Duration.ZERO);
    }

    private ControlLoop newControlLoop() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        return new ControlLoop(properties, state, threadPool, databasePool, rateLimiter,
                new SafetyManager(), new ControlLoopMetrics(registry), registry);
    }

    @Test
    void threadPoolIsNotGrownWhileThreadsWaitForDatabaseConnections() {
        when(databasePool.pendingConnections()).thenReturn(5);
        ControlLoop controlLoop = newControlLoop();

        var decisions = controlLoop.evaluateAll();

        verify(threadPool, never()).updatePoolSize(anyInt());
        assertThat(threadPoolSize.get()).isEqualTo(100);
        assertThat(decisions.get(ControlLoop.THREAD_POOL).applied()).isFalse();
    }

    @Test
    void inboundTrafficIsShedWhileThreadsWaitForDatabaseConnections() {
        when(databasePool.pendingConnections()).thenReturn(5);
        ControlLoop controlLoop = newControlLoop();

        controlLoop.evaluateAll();

        // The shed factor of 0.5 must have been applied after the loops ran.
        assertThat(rateLimit.get()).isLessThanOrEqualTo(500);
    }

    @Test
    void threadPoolIsGrownNormallyWhenTheDatabaseIsNotTheBottleneck() {
        when(databasePool.pendingConnections()).thenReturn(0);
        ControlLoop controlLoop = newControlLoop();

        controlLoop.evaluateAll();

        assertThat(threadPoolSize.get()).isGreaterThan(100);
    }

    @Test
    void emergencyStopPreventsEveryLoopFromApplyingChanges() {
        when(databasePool.pendingConnections()).thenReturn(0);
        state.engageEmergencyStop();
        ControlLoop controlLoop = newControlLoop();

        var decisions = controlLoop.evaluateAll();

        verify(threadPool, never()).updatePoolSize(anyInt());
        verify(rateLimiter, never()).updateLimit(anyInt());
        assertThat(decisions.values()).noneMatch(ControlDecision::applied);
    }

    @Test
    void masterSwitchOffKeepsEveryLoopInShadowMode() {
        when(databasePool.pendingConnections()).thenReturn(0);
        properties.setEnabled(false);
        ControlLoop controlLoop = newControlLoop();

        var decisions = controlLoop.evaluateAll();

        verify(threadPool, never()).updatePoolSize(anyInt());
        assertThat(decisions.values())
                .allMatch(d -> !d.applied() && "shadow mode".equals(d.reason()));
        // Shadow mode must still produce a usable recommendation.
        assertThat(decisions.get(ControlLoop.THREAD_POOL).requestedValue()).isGreaterThan(100);
    }
}
