package com.opentext.automatedruntimetuning.controller;

import com.opentext.automatedruntimetuning.config.TuningProperties;

/**
 * Discrete PID controller.
 *
 * <p>The controller uses the real elapsed time between invocations so that a delayed or
 * skipped tuning cycle does not distort the integral and derivative terms. The integral
 * accumulator is clamped to prevent windup, and any non-finite intermediate value causes
 * the controller to reset rather than propagate a corrupt value to a runtime resource.
 *
 * <p>Instances are stateful and must not be shared between control loops.
 */
public class PidController {

    /** Assumed interval for the first sample, and the ceiling for an abnormally long gap. */
    private static final double DEFAULT_DELTA_SECONDS = 1.0;
    private static final double MAX_DELTA_SECONDS = 60.0;

    private long lastSampleNanos;
    private double previousError;
    private double integralAccumulator;

    public synchronized PidOutput calculate(double target, double actual, TuningProperties.Loop config) {
        if (!Double.isFinite(target) || !Double.isFinite(actual)) {
            reset();
            return PidOutput.invalid();
        }

        double deltaSeconds = nextDeltaSeconds();
        double error = target - actual;

        double proportional = config.getKp() * error;

        integralAccumulator += error * deltaSeconds;
        double limit = Math.abs(config.getIntegralLimit());
        integralAccumulator = Math.max(-limit, Math.min(limit, integralAccumulator));
        double integral = config.getKi() * integralAccumulator;

        double derivative = config.getKd() * ((error - previousError) / deltaSeconds);
        previousError = error;

        double output = proportional + integral + derivative;

        PidOutput result = new PidOutput(error, proportional, integral, derivative, output, deltaSeconds);
        if (!result.finite()) {
            reset();
            return PidOutput.invalid();
        }
        return result;
    }

    public synchronized void reset() {
        lastSampleNanos = 0;
        previousError = 0;
        integralAccumulator = 0;
    }

    private double nextDeltaSeconds() {
        long now = System.nanoTime();
        double delta = DEFAULT_DELTA_SECONDS;
        if (lastSampleNanos != 0) {
            delta = (now - lastSampleNanos) / 1_000_000_000.0;
        }
        lastSampleNanos = now;
        if (!Double.isFinite(delta) || delta <= 0 || delta > MAX_DELTA_SECONDS) {
            return DEFAULT_DELTA_SECONDS;
        }
        return delta;
    }
}
