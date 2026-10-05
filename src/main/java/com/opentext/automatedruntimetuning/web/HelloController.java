package com.opentext.automatedruntimetuning.web;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sample workload endpoint used to generate traffic for load testing.
 *
 * <p>The optional delay parameter lets a test hold a request thread for a known duration,
 * which is what makes thread-pool pressure observable and the control loops measurable.
 */
@RestController
public class HelloController {

    private static final long MAX_DELAY_MILLIS = 10_000;

    @GetMapping("/hello")
    public String hello(@RequestParam(defaultValue = "0") long delayMillis) throws InterruptedException {
        if (delayMillis > 0) {
            Thread.sleep(Math.min(delayMillis, MAX_DELAY_MILLIS));
        }
        return "Hello from automated-runtime-tuning";
    }

    /** Simulates variable work so load tests produce a realistic latency distribution. */
    @GetMapping("/work")
    public String work(@RequestParam(defaultValue = "50") long baseMillis) throws InterruptedException {
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, baseMillis));
        Thread.sleep(Math.min(baseMillis + jitter, MAX_DELAY_MILLIS));
        return "done";
    }
}
