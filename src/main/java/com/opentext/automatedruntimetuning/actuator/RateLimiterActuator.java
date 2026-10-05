package com.opentext.automatedruntimetuning.actuator;

import com.opentext.automatedruntimetuning.config.TuningProperties;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Wraps a Resilience4j rate limiter whose permitted requests per second can be changed
 * at runtime. Rejected requests are counted so that shedding activity is visible.
 */
@Component
public class RateLimiterActuator {

    private static final String LIMITER_NAME = "inbound-requests";

    private final RateLimiter rateLimiter;
    private final Counter rejectedRequests;
    private final Counter permittedRequests;
    private final AtomicInteger configuredLimit = new AtomicInteger();

    public RateLimiterActuator(TuningProperties properties, MeterRegistry registry) {
        int initialLimit = properties.getRateLimiter().getDefaultValue();
        this.configuredLimit.set(initialLimit);
        this.rateLimiter = RateLimiter.of(LIMITER_NAME, RateLimiterConfig.custom()
                .limitForPeriod(initialLimit)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build());

        this.rejectedRequests = Counter.builder("ratelimiter.requests.rejected")
                .description("Requests rejected by the rate limiter")
                .register(registry);
        this.permittedRequests = Counter.builder("ratelimiter.requests.permitted")
                .description("Requests permitted by the rate limiter")
                .register(registry);

        Gauge.builder("ratelimiter.limit", configuredLimit, AtomicInteger::get)
                .description("Permitted requests per second currently in effect")
                .register(registry);
        Gauge.builder("ratelimiter.permissions.available", rateLimiter,
                        limiter -> limiter.getMetrics().getAvailablePermissions())
                .description("Permits remaining in the current refresh period")
                .register(registry);
    }

    public int currentLimit() {
        return configuredLimit.get();
    }

    public void updateLimit(int newLimit) {
        if (newLimit <= 0) {
            throw new IllegalArgumentException("Rate limit must be greater than zero");
        }
        rateLimiter.changeLimitForPeriod(newLimit);
        configuredLimit.set(newLimit);
    }

    /** Attempts to consume a permit without blocking. */
    public boolean tryAcquire() {
        boolean permitted = rateLimiter.acquirePermission(1);
        if (permitted) {
            permittedRequests.increment();
        } else {
            rejectedRequests.increment();
        }
        return permitted;
    }

    public double rejectedCount() {
        return rejectedRequests.count();
    }

    public double permittedCount() {
        return permittedRequests.count();
    }
}
