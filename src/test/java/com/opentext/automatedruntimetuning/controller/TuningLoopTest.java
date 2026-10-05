package com.opentext.automatedruntimetuning.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.opentext.automatedruntimetuning.config.TuningProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TuningLoopTest {

    private final SafetyManager safety = new SafetyManager();
    private ControlLoopMetrics metrics;
    private TuningProperties.Loop config;
    private AtomicInteger setting;
    private AtomicInteger measured;

    @BeforeEach
    void setUp() {
        metrics = new ControlLoopMetrics(new SimpleMeterRegistry());
        setting = new AtomicInteger(100);
        measured = new AtomicInteger(0);

        config = new TuningProperties.Loop();
        config.setEnabled(true);
        config.setTarget(50);
        config.setKp(1.0);
        config.setKi(0);
        config.setKd(0);
        config.setIntegralLimit(100);
        config.setMin(20);
        config.setMax(400);
        config.setMaxStep(25);
        config.setDeadband(2);
        config.setCooldown(Duration.ZERO);
        config.setDefaultValue(200);
    }

    private TuningLoop newLoop() {
        return new TuningLoop("test-loop", config,
                measured::get, setting::get, setting::set,
                safety, metrics);
    }

    @Test
    void shadowModeReportsARecommendationWithoutChangingTheResource() {
        measured.set(0);
        TuningLoop loop = newLoop();

        ControlDecision decision = loop.evaluate(false);

        assertThat(decision.applied()).isFalse();
        assertThat(decision.reason()).isEqualTo("shadow mode");
        assertThat(decision.requestedValue()).isNotEqualTo(decision.currentValue());
        assertThat(setting.get()).isEqualTo(100);
    }

    @Test
    void liveModeAppliesTheChange() {
        measured.set(0);
        TuningLoop loop = newLoop();

        ControlDecision decision = loop.evaluate(true);

        assertThat(decision.applied()).isTrue();
        assertThat(setting.get()).isEqualTo(decision.appliedValue());
    }

    @Test
    void changeIsLimitedToTheConfiguredMaximumStep() {
        measured.set(0);
        TuningLoop loop = newLoop();

        loop.evaluate(true);

        // Error of 50 with kp=1 asks for +50, but max-step allows only +25.
        assertThat(setting.get()).isEqualTo(125);
    }

    @Test
    void noChangeIsMadeWhileTheErrorIsInsideTheDeadband() {
        measured.set(49);
        TuningLoop loop = newLoop();

        ControlDecision decision = loop.evaluate(true);

        assertThat(decision.applied()).isFalse();
        assertThat(decision.reason()).isEqualTo("within deadband");
        assertThat(setting.get()).isEqualTo(100);
    }

    @Test
    void cooldownBlocksASecondChangeInQuickSuccession() {
        config.setCooldown(Duration.ofMinutes(5));
        measured.set(0);
        TuningLoop loop = newLoop();

        loop.evaluate(true);
        ControlDecision second = loop.evaluate(true);

        assertThat(second.applied()).isFalse();
        assertThat(second.reason()).isEqualTo("cooldown active");
        assertThat(setting.get()).isEqualTo(125);
    }

    @Test
    void valueNeverExceedsTheConfiguredMaximum() {
        config.setMax(110);
        config.setMaxStep(1000);
        measured.set(0);
        TuningLoop loop = newLoop();

        ControlDecision decision = loop.evaluate(true);

        assertThat(setting.get()).isEqualTo(110);
        assertThat(decision.atMax()).isTrue();
    }

    @Test
    void valueNeverDropsBelowTheConfiguredMinimum() {
        config.setMin(90);
        config.setMaxStep(1000);
        measured.set(1000);
        TuningLoop loop = newLoop();

        ControlDecision decision = loop.evaluate(true);

        assertThat(setting.get()).isEqualTo(90);
        assertThat(decision.atMin()).isTrue();
    }

    @Test
    void aFailingActuatorLeavesTheResourceUnchangedAndIsReported() {
        measured.set(0);
        TuningLoop loop = new TuningLoop("test-loop", config,
                measured::get,
                setting::get,
                value -> {
                    throw new IllegalStateException("actuator unavailable");
                },
                safety, metrics);

        ControlDecision decision = loop.evaluate(true);

        assertThat(decision.applied()).isFalse();
        assertThat(decision.reason()).contains("apply failed");
        assertThat(setting.get()).isEqualTo(100);
    }

    @Test
    void aFailingMeasurementDoesNotApplyAnything() {
        TuningLoop loop = new TuningLoop("test-loop", config,
                () -> {
                    throw new IllegalStateException("metric unavailable");
                },
                setting::get, setting::set, safety, metrics);

        ControlDecision decision = loop.evaluate(true);

        assertThat(decision.applied()).isFalse();
        assertThat(decision.reason()).contains("measurement failed");
        assertThat(setting.get()).isEqualTo(100);
    }

    @Test
    void resetRestoresTheConfiguredDefault() {
        setting.set(370);
        TuningLoop loop = newLoop();

        loop.reset();

        assertThat(setting.get()).isEqualTo(200);
    }

    @Test
    void aReverseActingLoopShrinksTheResourceWhenUtilisationIsBelowTarget() {
        // A capacity pool that is barely used should give capacity back, not grow.
        config.setReverseActing(true);
        config.setTarget(70);
        measured.set(0);
        TuningLoop loop = newLoop();

        loop.evaluate(true);

        assertThat(setting.get()).isLessThan(100);
    }

    @Test
    void aReverseActingLoopGrowsTheResourceWhenUtilisationIsAboveTarget() {
        config.setReverseActing(true);
        config.setTarget(70);
        measured.set(95);
        TuningLoop loop = newLoop();

        loop.evaluate(true);

        assertThat(setting.get()).isGreaterThan(100);
    }

    @Test
    void aDirectActingLoopRaisesTheResourceWhenTheSignalIsBelowTarget() {
        // The rate limiter is direct acting: idle capacity means admit more traffic.
        config.setReverseActing(false);
        config.setTarget(70);
        measured.set(0);
        TuningLoop loop = newLoop();

        loop.evaluate(true);

        assertThat(setting.get()).isGreaterThan(100);
    }
}
