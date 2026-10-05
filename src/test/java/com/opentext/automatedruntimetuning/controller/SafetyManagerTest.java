package com.opentext.automatedruntimetuning.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SafetyManagerTest {

    private final SafetyManager safety = new SafetyManager();

    @Test
    void clampKeepsValuesInsideTheConfiguredRange() {
        assertThat(safety.clamp(500, 20, 400)).isEqualTo(400);
        assertThat(safety.clamp(5, 20, 400)).isEqualTo(20);
        assertThat(safety.clamp(100, 20, 400)).isEqualTo(100);
    }

    @Test
    void stepLimitCapsGrowthAndShrinkage() {
        assertThat(safety.limitStep(100, 200, 25)).isEqualTo(125);
        assertThat(safety.limitStep(100, 10, 25)).isEqualTo(75);
        assertThat(safety.limitStep(100, 110, 25)).isEqualTo(110);
    }

    @Test
    void deadbandSuppressesInsignificantErrors() {
        assertThat(safety.withinDeadband(1.5, 2)).isTrue();
        assertThat(safety.withinDeadband(-1.5, 2)).isTrue();
        assertThat(safety.withinDeadband(3.0, 2)).isFalse();
    }

    @Test
    void nonFiniteValuesAreNeverConsideredUsable() {
        assertThat(safety.isUsable(Double.NaN)).isFalse();
        assertThat(safety.isUsable(Double.POSITIVE_INFINITY)).isFalse();
        assertThat(safety.isUsable(Double.NEGATIVE_INFINITY)).isFalse();
        assertThat(safety.isUsable(42.0)).isTrue();
    }

    @Test
    void deadbandRejectsNonFiniteErrorSoAFaultIsNotMistakenForStability() {
        assertThat(safety.withinDeadband(Double.NaN, 2)).isFalse();
    }
}
