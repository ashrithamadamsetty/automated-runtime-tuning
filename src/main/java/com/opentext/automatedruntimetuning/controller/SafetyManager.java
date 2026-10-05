package com.opentext.automatedruntimetuning.controller;

import org.springframework.stereotype.Component;

/**
 * Stateless guards applied to every controller output before it can reach a runtime
 * resource. These protect the application from an unstable or misconfigured controller.
 */
@Component
public class SafetyManager {

    /** Constrains a value to the configured operating range. */
    public int clamp(int value, int min, int max) {
        int low = Math.min(min, max);
        int high = Math.max(min, max);
        return Math.max(low, Math.min(high, value));
    }

    /**
     * Limits how far a value may move in a single cycle, preventing large abrupt
     * changes even when the controller demands one.
     */
    public int limitStep(int current, int desired, int maxStep) {
        if (maxStep <= 0) {
            return desired;
        }
        int delta = desired - current;
        if (delta > maxStep) {
            return current + maxStep;
        }
        if (delta < -maxStep) {
            return current - maxStep;
        }
        return desired;
    }

    /** True when the error is small enough that no adjustment is warranted. */
    public boolean withinDeadband(double error, double deadband) {
        return Double.isFinite(error) && Math.abs(error) <= Math.abs(deadband);
    }

    /** Rejects NaN and infinite values before they reach runtime configuration. */
    public boolean isUsable(double value) {
        return Double.isFinite(value);
    }
}
