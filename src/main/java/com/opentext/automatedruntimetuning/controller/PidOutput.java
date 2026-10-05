package com.opentext.automatedruntimetuning.controller;

/**
 * Result of a single PID evaluation, including the individual contribution of each
 * term so the controller's behaviour can be observed rather than guessed at.
 */
public record PidOutput(
        double error,
        double proportional,
        double integral,
        double derivative,
        double output,
        double deltaSeconds
) {

    public boolean finite() {
        return Double.isFinite(error)
                && Double.isFinite(proportional)
                && Double.isFinite(integral)
                && Double.isFinite(derivative)
                && Double.isFinite(output);
    }

    public static PidOutput invalid() {
        return new PidOutput(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0);
    }
}
