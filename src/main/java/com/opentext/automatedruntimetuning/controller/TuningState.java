package com.opentext.automatedruntimetuning.controller;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Holds the operational state of the tuning subsystem, independent of configuration.
 *
 * <p>Both the mode and the emergency stop are runtime flags rather than properties so
 * that an operator can change them immediately, without a restart or redeployment. The
 * configured {@code tuning.enabled} value only seeds the starting mode.
 */
@Component
public class TuningState {

    private static final Logger log = LoggerFactory.getLogger(TuningState.class);

    private final AtomicBoolean emergencyStopped = new AtomicBoolean(false);
    private final AtomicReference<TuningMode> mode = new AtomicReference<>();

    public boolean isEmergencyStopped() {
        return emergencyStopped.get();
    }

    public void engageEmergencyStop() {
        if (emergencyStopped.compareAndSet(false, true)) {
            log.warn("Emergency stop engaged: automatic runtime tuning is now disabled");
        }
    }

    public void releaseEmergencyStop() {
        if (emergencyStopped.compareAndSet(true, false)) {
            log.info("Emergency stop released: automatic runtime tuning may resume");
        }
    }

    /** Seeds the mode from configuration. Only the first call has any effect. */
    public void initialiseMode(TuningMode initial) {
        if (mode.compareAndSet(null, initial)) {
            log.info("Tuning mode initialised to {}", initial.wireName());
        }
    }

    public TuningMode getMode() {
        TuningMode current = mode.get();
        return current == null ? TuningMode.SHADOW : current;
    }

    /** @return the mode that was replaced. */
    public TuningMode setMode(TuningMode next) {
        TuningMode previous = mode.getAndSet(next);
        TuningMode effective = previous == null ? TuningMode.SHADOW : previous;
        if (effective != next) {
            log.warn("Tuning mode changed {} -> {}", effective.wireName(), next.wireName());
        }
        return effective;
    }
}