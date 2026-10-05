package com.opentext.automatedruntimetuning.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tuning")
public class TuningProperties {

    /** Master switch. When false no runtime change is ever applied (shadow mode only). */
    private boolean enabled = false;

    /** How often the control loops are evaluated. */
    private Duration interval = Duration.ofSeconds(10);

    private final Metrics metrics = new Metrics();
    private final Loop threadPool = Loop.threadPoolDefaults();
    private final Loop databasePool = Loop.databasePoolDefaults();
    private final Loop rateLimiter = Loop.rateLimiterDefaults();
    private final Backpressure backpressure = new Backpressure();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getInterval() {
        return interval;
    }

    public void setInterval(Duration interval) {
        this.interval = interval;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Loop getThreadPool() {
        return threadPool;
    }

    public Loop getDatabasePool() {
        return databasePool;
    }

    public Loop getRateLimiter() {
        return rateLimiter;
    }

    public Backpressure getBackpressure() {
        return backpressure;
    }

    public static class Metrics {

        private boolean endpointEnabled = true;

        public boolean isEndpointEnabled() {
            return endpointEnabled;
        }

        public void setEndpointEnabled(boolean endpointEnabled) {
            this.endpointEnabled = endpointEnabled;
        }
    }

    /**
     * Configuration for a single independent control loop.
     */
    public static class Loop {

        /** Enables automatic application of this loop's output. */
        private boolean enabled = false;

        /** Desired value of the measured signal. */
        private double target;

        private double kp;
        private double ki;
        private double kd;

        /** Absolute bound on the accumulated integral term (anti-windup). */
        private double integralLimit = 100;

        /** Hard floor for the controlled resource. */
        private int min;

        /** Hard ceiling for the controlled resource. */
        private int max;

        /** Largest change allowed in a single tuning cycle (slew rate limit). */
        private int maxStep = 10;

        /** Absolute error below which no change is made. */
        private double deadband = 1.0;

        /** Minimum time between two applied changes. */
        private Duration cooldown = Duration.ofSeconds(30);

        /** Value restored by a reset or emergency stop. */
        private int defaultValue;

        /**
         * True when increasing the controlled resource decreases the measured signal.
         *
         * <p>Capacity pools behave this way: adding threads or connections lowers
         * utilisation. Without this flag an idle pool would be read as "below target"
         * and grown, which is the opposite of the required behaviour.
         */
        private boolean reverseActing;

        static Loop threadPoolDefaults() {
            Loop loop = new Loop();
            loop.target = 70;
            loop.kp = 0.5;
            loop.ki = 0.05;
            loop.kd = 0.1;
            loop.integralLimit = 100;
            loop.min = 20;
            loop.max = 400;
            loop.maxStep = 25;
            loop.deadband = 5;
            loop.defaultValue = 200;
            loop.reverseActing = true;
            return loop;
        }

        static Loop databasePoolDefaults() {
            Loop loop = new Loop();
            loop.target = 70;
            loop.kp = 0.05;
            loop.ki = 0.005;
            loop.kd = 0.005;
            loop.integralLimit = 100;
            loop.min = 2;
            loop.max = 20;
            loop.maxStep = 2;
            loop.deadband = 10;
            loop.defaultValue = 10;
            loop.reverseActing = true;
            return loop;
        }

        static Loop rateLimiterDefaults() {
            Loop loop = new Loop();
            loop.target = 70;
            loop.kp = 0.6;
            loop.ki = 0.05;
            loop.kd = 0.05;
            loop.integralLimit = 200;
            loop.min = 10;
            loop.max = 5000;
            loop.maxStep = 50;
            loop.deadband = 5;
            loop.defaultValue = 200;
            return loop;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getTarget() {
            return target;
        }

        public void setTarget(double target) {
            this.target = target;
        }

        public double getKp() {
            return kp;
        }

        public void setKp(double kp) {
            this.kp = kp;
        }

        public double getKi() {
            return ki;
        }

        public void setKi(double ki) {
            this.ki = ki;
        }

        public double getKd() {
            return kd;
        }

        public void setKd(double kd) {
            this.kd = kd;
        }

        public double getIntegralLimit() {
            return integralLimit;
        }

        public void setIntegralLimit(double integralLimit) {
            this.integralLimit = integralLimit;
        }

        public int getMin() {
            return min;
        }

        public void setMin(int min) {
            this.min = min;
        }

        public int getMax() {
            return max;
        }

        public void setMax(int max) {
            this.max = max;
        }

        public int getMaxStep() {
            return maxStep;
        }

        public void setMaxStep(int maxStep) {
            this.maxStep = maxStep;
        }

        public double getDeadband() {
            return deadband;
        }

        public void setDeadband(double deadband) {
            this.deadband = deadband;
        }

        public Duration getCooldown() {
            return cooldown;
        }

        public void setCooldown(Duration cooldown) {
            this.cooldown = cooldown;
        }

        public int getDefaultValue() {
            return defaultValue;
        }

        public void setDefaultValue(int defaultValue) {
            this.defaultValue = defaultValue;
        }

        public boolean isReverseActing() {
            return reverseActing;
        }

        public void setReverseActing(boolean reverseActing) {
            this.reverseActing = reverseActing;
        }
    }

    /**
     * Controls how the system reacts when threads are blocked waiting for database
     * connections rather than genuinely busy doing useful work.
     */
    public static class Backpressure {

        private boolean enabled = true;

        /** Threads awaiting a DB connection that indicates downstream starvation. */
        private int pendingConnectionThreshold = 1;

        /** Multiplier applied to the rate limit when starvation is detected. */
        private double shedFactor = 0.8;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPendingConnectionThreshold() {
            return pendingConnectionThreshold;
        }

        public void setPendingConnectionThreshold(int pendingConnectionThreshold) {
            this.pendingConnectionThreshold = pendingConnectionThreshold;
        }

        public double getShedFactor() {
            return shedFactor;
        }

        public void setShedFactor(double shedFactor) {
            this.shedFactor = shedFactor;
        }
    }
}
