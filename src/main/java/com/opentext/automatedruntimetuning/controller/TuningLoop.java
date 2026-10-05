package com.opentext.automatedruntimetuning.controller;

import com.opentext.automatedruntimetuning.config.TuningProperties;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One independent control loop binding a measured signal to a runtime resource.
 *
 * <p>Each loop owns its own PID state so that loops never interfere with one another.
 * Every evaluation produces a {@link ControlDecision}, whether or not a change was
 * applied, which makes shadow mode and live mode observationally identical apart from
 * the final write.
 */
public class TuningLoop {

    private static final Logger log = LoggerFactory.getLogger(TuningLoop.class);

    private final String name;
    private final TuningProperties.Loop config;
    private final IntSupplier measuredSignal;
    private final IntSupplier currentSetting;
    private final IntConsumer applier;
    private final SafetyManager safety;
    private final ControlLoopMetrics metrics;
    private final PidController pid = new PidController();

    private volatile long lastChangeNanos;
    private volatile ControlDecision lastDecision;

    public TuningLoop(String name,
                      TuningProperties.Loop config,
                      IntSupplier measuredSignal,
                      IntSupplier currentSetting,
                      IntConsumer applier,
                      SafetyManager safety,
                      ControlLoopMetrics metrics) {
        this.name = name;
        this.config = config;
        this.measuredSignal = measuredSignal;
        this.currentSetting = currentSetting;
        this.applier = applier;
        this.safety = safety;
        this.metrics = metrics;
    }

    public String name() {
        return name;
    }

    public TuningProperties.Loop config() {
        return config;
    }

    public ControlDecision lastDecision() {
        return lastDecision;
    }

    /**
     * Evaluates the loop once.
     *
     * @param allowApply when false the loop runs in shadow mode: the controller advances
     *                   and metrics are published, but no runtime resource is modified.
     */
    public synchronized ControlDecision evaluate(boolean allowApply) {
        int current;
        int actual;
        try {
            current = currentSetting.getAsInt();
            actual = measuredSignal.getAsInt();
        } catch (RuntimeException e) {
            metrics.recordFault(name);
            log.warn("[{}] unable to read runtime state, loop skipped: {}", name, e.toString());
            return publish(ControlDecision.skipped(name, config.getTarget(), Double.NaN, 0,
                    "measurement failed: " + e.getMessage()));
        }

        PidOutput output = pid.calculate(config.getTarget(), actual, config);
        if (!output.finite() || !safety.isUsable(output.output())) {
            metrics.recordFault(name);
            pid.reset();
            log.warn("[{}] controller produced a non-finite value; state reset, no change applied", name);
            return publish(ControlDecision.skipped(name, config.getTarget(), actual, current,
                    "non-finite controller output"));
        }

        // A reverse-acting plant responds to the controller in the opposite direction:
        // adding capacity reduces the measured utilisation, so the demanded change must
        // be inverted before it is applied.
        double demand = config.isReverseActing() ? -output.output() : output.output();

        int desired = (int) Math.round(current + demand);
        int bounded = safety.clamp(desired, config.getMin(), config.getMax());
        int candidate = safety.limitStep(current, bounded, config.getMaxStep());

        boolean atMin = candidate <= config.getMin();
        boolean atMax = candidate >= config.getMax();

        String blockedReason = blockingReason(output, candidate, current, allowApply);
        boolean applied = false;

        if (blockedReason == null) {
            try {
                applier.accept(candidate);
                applied = true;
                lastChangeNanos = System.nanoTime();
                log.info("[{}] target={} actual={} error={} p={} i={} d={} {} -> {}",
                        name,
                        fmt(config.getTarget()), actual, fmt(output.error()),
                        fmt(output.proportional()), fmt(output.integral()), fmt(output.derivative()),
                        current, candidate);
            } catch (RuntimeException e) {
                metrics.recordFault(name);
                blockedReason = "apply failed: " + e.getMessage();
                log.error("[{}] failed to apply value {}; resource left at {}", name, candidate, current, e);
            }
        }

        return publish(new ControlDecision(
                name,
                config.getTarget(),
                actual,
                output.error(),
                output.proportional(),
                output.integral(),
                output.derivative(),
                current,
                candidate,
                applied ? candidate : current,
                applied,
                applied ? "applied" : blockedReason,
                atMin,
                atMax));
    }

    /** Restores the configured default and clears accumulated controller state. */
    public synchronized void reset() {
        pid.reset();
        lastChangeNanos = 0;
        try {
            applier.accept(config.getDefaultValue());
            log.info("[{}] reset to default value {}", name, config.getDefaultValue());
        } catch (RuntimeException e) {
            metrics.recordFault(name);
            log.error("[{}] failed to restore default value {}", name, config.getDefaultValue(), e);
        }
    }

    private String blockingReason(PidOutput output, int candidate, int current, boolean allowApply) {
        if (!allowApply) {
            return "shadow mode";
        }
        if (safety.withinDeadband(output.error(), config.getDeadband())) {
            return "within deadband";
        }
        if (candidate == current) {
            return "no change required";
        }
        if (inCooldown()) {
            return "cooldown active";
        }
        return null;
    }

    private boolean inCooldown() {
        if (lastChangeNanos == 0 || config.getCooldown() == null) {
            return false;
        }
        long elapsedNanos = System.nanoTime() - lastChangeNanos;
        return elapsedNanos < config.getCooldown().toNanos();
    }

    private ControlDecision publish(ControlDecision decision) {
        lastDecision = decision;
        metrics.record(decision);
        return decision;
    }

    private static String fmt(double value) {
        return String.format("%.2f", value);
    }
}
