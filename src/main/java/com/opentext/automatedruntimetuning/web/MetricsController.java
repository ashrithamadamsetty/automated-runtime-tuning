package com.opentext.automatedruntimetuning.web;

import com.opentext.automatedruntimetuning.config.TuningProperties;
import com.opentext.automatedruntimetuning.metrics.MetricsCollector;
import com.opentext.automatedruntimetuning.metrics.RuntimeMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MetricsController {

    private final MetricsCollector metricsCollector;
    private final TuningProperties tuningProperties;

    public MetricsController(MetricsCollector metricsCollector, TuningProperties tuningProperties) {
        this.metricsCollector = metricsCollector;
        this.tuningProperties = tuningProperties;
    }

    @GetMapping("/runtime-metrics")
    public RuntimeMetrics runtimeMetrics() {
        if (!tuningProperties.getMetrics().isEndpointEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Runtime metrics endpoint is disabled");
        }
        return metricsCollector.snapshot();
    }
}
