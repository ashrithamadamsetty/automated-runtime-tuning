package com.opentext.automatedruntimetuning.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the control loops on a fixed cadence.
 *
 * <p>Any failure is contained here: the scheduler never propagates an exception, so a
 * controller fault leaves the application running on its last known-good configuration.
 */
@Component
public class ControlLoopScheduler {

    private static final Logger log = LoggerFactory.getLogger(ControlLoopScheduler.class);

    private final ControlLoop controlLoop;

    public ControlLoopScheduler(ControlLoop controlLoop) {
        this.controlLoop = controlLoop;
    }

    @Scheduled(fixedDelayString = "${tuning.interval:PT10S}")
    public void run() {
        try {
            controlLoop.evaluateAll();
        } catch (RuntimeException e) {
            log.error("Tuning cycle failed; runtime configuration left unchanged", e);
        }
    }
}
