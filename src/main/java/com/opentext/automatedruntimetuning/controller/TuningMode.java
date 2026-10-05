package com.opentext.automatedruntimetuning.controller;

import java.util.Locale;

/**
 * Operating mode of the tuning subsystem.
 *
 * <p>Separating OFF from SHADOW matters operationally: shadow still costs a measurement
 * and a controller step every cycle, which is what makes it useful for validation, but
 * there are times when the whole subsystem should simply stand down.
 */
public enum TuningMode {

    /** No evaluation at all. The control loops do not run. */
    OFF,

    /** Loops evaluate and publish decisions, but never modify a runtime resource. */
    SHADOW,

    /** Loops evaluate and apply their output, subject to the safety pipeline. */
    ACTIVE;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TuningMode from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Mode is required: off, shadow or active");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown mode '" + value + "'. Expected one of: off, shadow, active");
        }
    }
}