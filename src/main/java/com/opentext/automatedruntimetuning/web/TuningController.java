package com.opentext.automatedruntimetuning.web;

import com.opentext.automatedruntimetuning.actuator.DatabasePoolActuator;
import com.opentext.automatedruntimetuning.actuator.RateLimiterActuator;
import com.opentext.automatedruntimetuning.actuator.ThreadPoolActuator;
import com.opentext.automatedruntimetuning.audit.AuditEntry;
import com.opentext.automatedruntimetuning.audit.AuditLog;
import com.opentext.automatedruntimetuning.config.TuningProperties;
import com.opentext.automatedruntimetuning.controller.ControlDecision;
import com.opentext.automatedruntimetuning.controller.ControlLoop;
import com.opentext.automatedruntimetuning.controller.TuningLoop;
import com.opentext.automatedruntimetuning.controller.TuningMode;
import com.opentext.automatedruntimetuning.controller.TuningState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operational interface for the tuning subsystem: inspection, mode control, reset and
 * emergency stop. These endpoints are exempt from rate limiting so that the system stays
 * controllable while load is being shed.
 *
 * <p>There is deliberately no endpoint for setting a resource value directly. Resource
 * values are owned by the controllers; a manual write would be silently reverted on the
 * next cycle and would make the audit trail misleading.
 */
@RestController
@RequestMapping("/tuning")
public class TuningController {

    private static final Logger log = LoggerFactory.getLogger(TuningController.class);

    private final ControlLoop controlLoop;
    private final TuningState tuningState;
    private final TuningProperties properties;
    private final AuditLog audit;
    private final ThreadPoolActuator threadPoolActuator;
    private final DatabasePoolActuator databasePoolActuator;
    private final RateLimiterActuator rateLimiterActuator;

    public TuningController(ControlLoop controlLoop,
                            TuningState tuningState,
                            TuningProperties properties,
                            AuditLog audit,
                            ThreadPoolActuator threadPoolActuator,
                            DatabasePoolActuator databasePoolActuator,
                            RateLimiterActuator rateLimiterActuator) {
        this.controlLoop = controlLoop;
        this.tuningState = tuningState;
        this.properties = properties;
        this.audit = audit;
        this.threadPoolActuator = threadPoolActuator;
        this.databasePoolActuator = databasePoolActuator;
        this.rateLimiterActuator = rateLimiterActuator;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> loops = new LinkedHashMap<>();
        controlLoop.loops().forEach((name, loop) -> loops.put(name, describe(loop)));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("enabled", tuningState.getMode() == TuningMode.ACTIVE);
        response.put("emergencyStopped", tuningState.isEmergencyStopped());
        response.put("interval", properties.getInterval().toString());
        response.put("mode", tuningState.getMode().wireName());
        response.put("effectiveMode", effectiveMode());
        response.put("loops", loops);
        response.put("resources", resources());
        return response;
    }

    /** Switches between off, shadow and active without a restart. */
    @PostMapping("/mode")
    public Map<String, Object> setMode(@RequestParam("value") String value) {
        TuningMode next = TuningMode.from(value);
        TuningMode previous = tuningState.setMode(next);
        audit.event(null, next.wireName(),
                "mode changed: " + previous.wireName() + " -> " + next.wireName());
        log.info("Tuning mode set to {} by operator", next.wireName());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("previous", previous.wireName());
        response.put("mode", next.wireName());
        response.put("effectiveMode", effectiveMode());
        return response;
    }

    @GetMapping("/loops/{name}")
    public Map<String, Object> loop(@PathVariable String name) {
        return describe(controlLoop.loop(name));
    }

    /** Enables or disables a single loop so instability can be attributed to one of them. */
    @PostMapping("/loops/{name}/enabled")
    public Map<String, Object> setLoopEnabled(@PathVariable String name,
                                              @RequestParam("value") boolean enabled) {
        TuningLoop loop = controlLoop.loop(name);
        boolean previous = loop.config().isEnabled();
        loop.config().setEnabled(enabled);
        audit.event(name, tuningState.getMode().wireName(),
                "loop " + (enabled ? "enabled" : "disabled") + " by operator");
        log.info("Control loop {} {} by operator", name, enabled ? "enabled" : "disabled");
        return Map.of("loop", name, "previous", previous, "enabled", enabled);
    }

    /** Forces an immediate evaluation instead of waiting for the next scheduled cycle. */
    @PostMapping("/evaluate")
    public Map<String, ControlDecision> evaluate() {
        return controlLoop.evaluateAll();
    }

    @GetMapping("/audit")
    public List<AuditEntry> audit(@RequestParam(defaultValue = "100") int limit) {
        return audit.recent(limit);
    }

    @PostMapping("/audit/clear")
    public Map<String, Object> clearAudit() {
        audit.clear();
        return Map.of("status", "cleared");
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        controlLoop.resetAll();
        return Map.of("status", "reset", "resources", resources());
    }

    @PostMapping("/emergency-stop")
    public Map<String, Object> emergencyStop(
            @RequestParam(defaultValue = "true") boolean restoreDefaults) {
        tuningState.engageEmergencyStop();
        audit.event(null, tuningState.getMode().wireName(), "EMERGENCY STOP engaged by operator");
        if (restoreDefaults) {
            controlLoop.resetAll();
        }
        return Map.of(
                "status", "emergency-stop-engaged",
                "restoredDefaults", restoreDefaults,
                "resources", resources());
    }

    @PostMapping("/emergency-stop/release")
    public Map<String, Object> releaseEmergencyStop() {
        tuningState.releaseEmergencyStop();
        audit.event(null, tuningState.getMode().wireName(), "emergency stop released by operator");
        return Map.of("status", "emergency-stop-released", "mode", tuningState.getMode().wireName());
    }

    @GetMapping("/thread-pool")
    public Map<String, Object> threadPool() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("target", ControlLoop.THREAD_POOL);
        response.put("maxThreads", threadPoolActuator.currentPoolSize());
        response.put("activeThreads", threadPoolActuator.activeThreads());
        response.put("poolSize", threadPoolActuator.poolSize());
        response.put("queueSize", threadPoolActuator.queueSize());
        response.put("utilizationPercent", threadPoolActuator.utilizationPercent());
        return response;
    }

    @GetMapping("/database-pool")
    public Map<String, Object> databasePool() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("target", ControlLoop.DATABASE_POOL);
        response.put("maxPoolSize", databasePoolActuator.maxPoolSize());
        response.put("totalConnections", databasePoolActuator.currentPoolSize());
        response.put("activeConnections", databasePoolActuator.activeConnections());
        response.put("idleConnections", databasePoolActuator.idleConnections());
        response.put("pendingThreads", databasePoolActuator.pendingConnections());
        response.put("dbaMaxConnections", databasePoolActuator.dbaMaximumConnections());
        return response;
    }

    @GetMapping("/rate-limiter")
    public Map<String, Object> rateLimiter() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("target", ControlLoop.RATE_LIMITER);
        response.put("limitPerSecond", rateLimiterActuator.currentLimit());
        response.put("permitted", rateLimiterActuator.permittedCount());
        response.put("rejected", rateLimiterActuator.rejectedCount());
        return response;
    }

    private Map<String, Object> describe(TuningLoop loop) {
        TuningProperties.Loop config = loop.config();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("enabled", config.isEnabled());
        settings.put("target", config.getTarget());
        settings.put("reverseActing", config.isReverseActing());
        settings.put("kp", config.getKp());
        settings.put("ki", config.getKi());
        settings.put("kd", config.getKd());
        settings.put("min", config.getMin());
        settings.put("max", config.getMax());
        settings.put("maxStep", config.getMaxStep());
        settings.put("deadband", config.getDeadband());
        settings.put("cooldown", String.valueOf(config.getCooldown()));
        settings.put("defaultValue", config.getDefaultValue());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", loop.name());
        response.put("config", settings);
        response.put("lastDecision", loop.lastDecision());
        return response;
    }

    private Map<String, Object> resources() {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("threadPoolMax", threadPoolActuator.currentPoolSize());
        resources.put("databasePoolMax", databasePoolActuator.maxPoolSize());
        resources.put("rateLimitPerSecond", rateLimiterActuator.currentLimit());
        return resources;
    }

    private String effectiveMode() {
        return tuningState.isEmergencyStopped()
                ? "emergency-stopped"
                : tuningState.getMode().wireName();
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> handleRejectedChange(RuntimeException e) {
        log.warn("Rejected tuning request: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "rejected");
        body.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
}