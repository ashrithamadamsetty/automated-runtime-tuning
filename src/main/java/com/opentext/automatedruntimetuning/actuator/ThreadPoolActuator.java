package com.opentext.automatedruntimetuning.actuator;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.ProtocolHandler;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Reads and modifies the embedded Tomcat request thread pool at runtime.
 *
 * <p>The pool is resolved lazily because the web server does not exist while the
 * application context is still being built.
 */
@Component
public class ThreadPoolActuator {

    private final ObjectProvider<ServletWebServerApplicationContext> contextProvider;

    public ThreadPoolActuator(ObjectProvider<ServletWebServerApplicationContext> contextProvider,
                              MeterRegistry registry) {
        this.contextProvider = contextProvider;
        registerMetrics(registry);
    }

    /** Configured maximum number of request-handling threads. */
    public int currentPoolSize() {
        ThreadPoolExecutor executor = executor();
        return executor != null ? executor.getMaximumPoolSize() : 0;
    }

    /** Threads currently executing a request. */
    public int activeThreads() {
        ThreadPoolExecutor executor = executor();
        return executor != null ? executor.getActiveCount() : 0;
    }

    /** Threads that exist in the pool, busy or idle. */
    public int poolSize() {
        ThreadPoolExecutor executor = executor();
        return executor != null ? executor.getPoolSize() : 0;
    }

    /** Requests accepted but not yet assigned to a thread. */
    public int queueSize() {
        ThreadPoolExecutor executor = executor();
        return executor != null ? executor.getQueue().size() : 0;
    }

    /** Tasks submitted to the pool and not yet completed. */
    public int submittedCount() {
        ThreadPoolExecutor executor = executor();
        return executor != null ? (int) executor.getSubmittedCount() : 0;
    }

    /** Percentage of the configured maximum currently in use. */
    public int utilizationPercent() {
        int max = currentPoolSize();
        if (max <= 0) {
            return 0;
        }
        return (int) Math.round((activeThreads() * 100.0) / max);
    }

    public void updatePoolSize(int newSize) {
        if (newSize <= 0) {
            throw new IllegalArgumentException("Thread pool size must be greater than zero");
        }
        ThreadPoolExecutor executor = executor();
        if (executor == null) {
            throw new IllegalStateException("Tomcat thread pool is not available");
        }
        // The core size must never exceed the maximum, or the executor rejects the change.
        if (newSize < executor.getCorePoolSize()) {
            executor.setCorePoolSize(newSize);
        }
        executor.setMaximumPoolSize(newSize);
    }

    private void registerMetrics(MeterRegistry registry) {
        Gauge.builder("apptuning.threadpool.max", this, ThreadPoolActuator::currentPoolSize)
                .description("Configured maximum request threads")
                .register(registry);
        Gauge.builder("apptuning.threadpool.active", this, ThreadPoolActuator::activeThreads)
                .description("Request threads currently executing")
                .register(registry);
        Gauge.builder("apptuning.threadpool.size", this, ThreadPoolActuator::poolSize)
                .description("Threads currently present in the pool")
                .register(registry);
        Gauge.builder("apptuning.threadpool.queue", this, ThreadPoolActuator::queueSize)
                .description("Requests waiting for a thread")
                .register(registry);
        Gauge.builder("apptuning.threadpool.submitted", this, ThreadPoolActuator::submittedCount)
                .description("Submitted tasks not yet completed")
                .register(registry);
        Gauge.builder("apptuning.threadpool.utilization", this, ThreadPoolActuator::utilizationPercent)
                .description("Percentage of maximum request threads in use")
                .register(registry);
    }

    private ThreadPoolExecutor executor() {
        ServletWebServerApplicationContext context = contextProvider.getIfAvailable();
        if (context == null) {
            return null;
        }
        if (!(context.getWebServer() instanceof TomcatWebServer webServer)) {
            return null;
        }
        Tomcat tomcat = webServer.getTomcat();
        if (tomcat == null || tomcat.getConnector() == null) {
            return null;
        }
        ProtocolHandler handler = tomcat.getConnector().getProtocolHandler();
        if (handler.getExecutor() instanceof ThreadPoolExecutor executor) {
            return executor;
        }
        return null;
    }
}
