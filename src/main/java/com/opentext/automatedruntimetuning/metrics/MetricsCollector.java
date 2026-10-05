package com.opentext.automatedruntimetuning.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class MetricsCollector {

    private final MeterRegistry meterRegistry;

    public MetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public RuntimeMetrics snapshot() {
        return new RuntimeMetrics(
                readGauge("system.cpu.usage"),
                (long) readGauge("jvm.memory.used", "area", "heap"),
                (long) readGauge("jvm.memory.max", "area", "heap"),
                (int) readGauge("jvm.threads.live"),
                (int) readGauge("jvm.threads.peak")
        );
    }

    private double readGauge(String name, String... tags) {
        var gauge = meterRegistry.find(name).tags(tags).gauge();
        return gauge != null ? gauge.value() : Double.NaN;
    }
}