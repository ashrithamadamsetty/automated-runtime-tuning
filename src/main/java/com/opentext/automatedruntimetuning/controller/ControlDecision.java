package com.opentext.automatedruntimetuning.controller;

/**
 * Immutable record of one control-loop evaluation. This is the unit of observability for
 * the tuning system: it captures what the controller saw, what it wanted, what it was
 * allowed to do, and why.
 */
public record ControlDecision(
        String loop,
        double target,
        double actual,
        double error,
        double proportional,
        double integral,
        double derivative,
        int currentValue,
        int requestedValue,
        int appliedValue,
        boolean applied,
        String reason,
        boolean atMin,
        boolean atMax
) {

    public static ControlDecision skipped(String loop,
                                          double target,
                                          double actual,
                                          int currentValue,
                                          String reason) {
        return new ControlDecision(loop, target, actual, target - actual,
                0, 0, 0,
                currentValue, currentValue, currentValue,
                false, reason, false, false);
    }
}
