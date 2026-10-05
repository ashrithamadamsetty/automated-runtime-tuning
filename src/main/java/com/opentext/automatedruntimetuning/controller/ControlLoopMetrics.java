package com.opentext.automatedruntimetuning.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Publishes the internal state of each control loop as Micrometer time series so the
 * controller's behaviour can be charted in Grafana alongside application metrics.
 *
 * <p>Only the loop name is used as a tag, keeping cardinality fixed and bounded.
 */
@Component
public class ControlLoopMetrics {

    private final MeterRegistry registry;
    private final Map<String, LoopMeters> loops = new ConcurrentHashMap<>();

    public ControlLoopMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(ControlDecision decision) {
        LoopMeters meters = loops.computeIfAbsent(decision.loop(), this::create);
        meters.target.set(decision.target());
        meters.actual.set(decision.actual());
        meters.error.set(decision.error());
        meters.proportional.set(decision.proportional());
        meters.integral.set(decision.integral());
        meters.derivative.set(decision.derivative());
        meters.requested.set((double) decision.requestedValue());
        meters.applied.set((double) decision.appliedValue());
        meters.atMin.set(decision.atMin() ? 1d : 0d);
        meters.atMax.set(decision.atMax() ? 1d : 0d);

        if (decision.applied()) {
            meters.changes.increment();
        } else {
            meters.skipped.increment();
        }
    }

    public void recordFault(String loop) {
        loops.computeIfAbsent(loop, this::create).faults.increment();
    }

    private LoopMeters create(String loop) {
        Tags tags = Tags.of("loop", loop);
        LoopMeters meters = new LoopMeters();
        gauge("tuning.loop.target", tags, meters.target, "Configured target value for the loop");
        gauge("tuning.loop.actual", tags, meters.actual, "Measured value of the controlled signal");
        gauge("tuning.loop.error", tags, meters.error, "Difference between target and actual");
        gauge("tuning.loop.proportional", tags, meters.proportional, "Proportional term contribution");
        gauge("tuning.loop.integral", tags, meters.integral, "Integral term contribution");
        gauge("tuning.loop.derivative", tags, meters.derivative, "Derivative term contribution");
        gauge("tuning.loop.requested", tags, meters.requested, "Value the controller asked for");
        gauge("tuning.loop.applied", tags, meters.applied, "Value actually in effect");
        gauge("tuning.loop.at.min", tags, meters.atMin, "1 when the loop is pinned at its minimum");
        gauge("tuning.loop.at.max", tags, meters.atMax, "1 when the loop is pinned at its maximum");

        meters.changes = Counter.builder("tuning.loop.changes")
                .description("Number of applied runtime changes")
                .tags(tags).register(registry);
        meters.skipped = Counter.builder("tuning.loop.skipped")
                .description("Number of evaluations that did not result in a change")
                .tags(tags).register(registry);
        meters.faults = Counter.builder("tuning.loop.faults")
                .description("Number of controller faults")
                .tags(tags).register(registry);
        return meters;
    }

    private void gauge(String name, Tags tags, AtomicReference<Double> holder, String description) {
        io.micrometer.core.instrument.Gauge.builder(name, holder, ref -> {
                    Double value = ref.get();
                    return value == null ? Double.NaN : value;
                })
                .description(description)
                .tags(tags)
                .register(registry);
    }

    private static final class LoopMeters {
        private final AtomicReference<Double> target = new AtomicReference<>(0d);
        private final AtomicReference<Double> actual = new AtomicReference<>(0d);
        private final AtomicReference<Double> error = new AtomicReference<>(0d);
        private final AtomicReference<Double> proportional = new AtomicReference<>(0d);
        private final AtomicReference<Double> integral = new AtomicReference<>(0d);
        private final AtomicReference<Double> derivative = new AtomicReference<>(0d);
        private final AtomicReference<Double> requested = new AtomicReference<>(0d);
        private final AtomicReference<Double> applied = new AtomicReference<>(0d);
        private final AtomicReference<Double> atMin = new AtomicReference<>(0d);
        private final AtomicReference<Double> atMax = new AtomicReference<>(0d);
        private Counter changes;
        private Counter skipped;
        private Counter faults;
    }
}
