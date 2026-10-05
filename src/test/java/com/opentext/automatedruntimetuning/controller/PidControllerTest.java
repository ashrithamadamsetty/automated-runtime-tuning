package com.opentext.automatedruntimetuning.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.opentext.automatedruntimetuning.config.TuningProperties;
import org.junit.jupiter.api.Test;

class PidControllerTest {

    private static TuningProperties.Loop config(double kp, double ki, double kd, double integralLimit) {
        TuningProperties.Loop loop = new TuningProperties.Loop();
        loop.setKp(kp);
        loop.setKi(ki);
        loop.setKd(kd);
        loop.setIntegralLimit(integralLimit);
        return loop;
    }

    @Test
    void producesProportionalResponseToError() {
        PidController pid = new PidController();

        PidOutput output = pid.calculate(100, 60, config(0.5, 0, 0, 100));

        assertThat(output.error()).isEqualTo(40);
        assertThat(output.proportional()).isEqualTo(20);
        assertThat(output.output()).isEqualTo(20);
    }

    @Test
    void firstSampleUsesADefaultIntervalSoTheDerivativeCannotExplode() {
        PidController pid = new PidController();

        PidOutput output = pid.calculate(100, 0, config(0, 0, 1, 100));

        assertThat(output.deltaSeconds()).isEqualTo(1.0);
        assertThat(output.derivative()).isFinite();
    }

    @Test
    void integralTermIsClampedToPreventWindup() {
        PidController pid = new PidController();
        TuningProperties.Loop config = config(0, 1, 0, 5);

        PidOutput output = null;
        for (int i = 0; i < 100; i++) {
            output = pid.calculate(1000, 0, config);
        }

        assertThat(output).isNotNull();
        assertThat(Math.abs(output.integral())).isLessThanOrEqualTo(5.0);
    }

    @Test
    void nonFiniteInputIsRejectedRatherThanPropagated() {
        PidController pid = new PidController();

        PidOutput output = pid.calculate(100, Double.NaN, config(0.5, 0.1, 0.1, 100));

        assertThat(output.finite()).isFalse();
    }

    @Test
    void resetClearsAccumulatedState() {
        PidController pid = new PidController();
        TuningProperties.Loop config = config(0, 1, 0, 1000);
        for (int i = 0; i < 20; i++) {
            pid.calculate(100, 0, config);
        }

        pid.reset();
        PidOutput afterReset = pid.calculate(100, 0, config);

        // A fresh controller uses the 1 second default interval, so one sample of
        // error 100 must accumulate exactly 100.
        assertThat(afterReset.integral()).isEqualTo(100);
    }

    @Test
    void errorIsNegativeWhenTheActualValueExceedsTheTarget() {
        PidController pid = new PidController();

        PidOutput output = pid.calculate(50, 80, config(1, 0, 0, 100));

        assertThat(output.error()).isEqualTo(-30);
        assertThat(output.output()).isNegative();
    }
}
