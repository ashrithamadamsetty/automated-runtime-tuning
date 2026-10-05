package com.opentext.automatedruntimetuning.metrics;

public record RuntimeMetrics(
        double systemCpuUsage,
        long heapUsedBytes,
        long heapMaxBytes,
        int liveThreads,
        int peakThreads
) {
}