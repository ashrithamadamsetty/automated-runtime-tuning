package com.opentext.automatedruntimetuning.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opentext.automatedruntimetuning.actuator.DatabasePoolActuator;
import com.opentext.automatedruntimetuning.actuator.RateLimiterActuator;
import com.opentext.automatedruntimetuning.actuator.ThreadPoolActuator;
import com.opentext.automatedruntimetuning.audit.AuditLog;
import com.opentext.automatedruntimetuning.controller.ControlLoop;
import com.opentext.automatedruntimetuning.controller.TuningMode;
import com.opentext.automatedruntimetuning.controller.TuningState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * An operator request refused by the tuning subsystem must come back as a rejected
 * request carrying the reason, not as an opaque server error.
 */
@WebMvcTest(TuningController.class)
class TuningControllerErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ControlLoop controlLoop;
    @MockitoBean
    private TuningState tuningState;
    @MockitoBean
    private AuditLog auditLog;
    @MockitoBean
    private ThreadPoolActuator threadPoolActuator;
    @MockitoBean
    private DatabasePoolActuator databasePoolActuator;
    @MockitoBean
    private RateLimiterActuator rateLimiterActuator;

    @BeforeEach
    void setUp() {
        when(tuningState.getMode()).thenReturn(TuningMode.SHADOW);
    }

    @Test
    void anUnknownModeIsRejectedWithTheAcceptedValues() throws Exception {
        mockMvc.perform(post("/tuning/mode").param("value", "turbo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("rejected"))
                .andExpect(jsonPath("$.message").value(
                        "Unknown mode 'turbo'. Expected one of: off, shadow, active"));
    }

    @Test
    void aBlankModeIsRejected() throws Exception {
        mockMvc.perform(post("/tuning/mode").param("value", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Mode is required: off, shadow or active"));
    }

    @Test
    void togglingAnUnknownLoopIsRejectedWithAnExplanation() throws Exception {
        when(controlLoop.loop("cache-pool"))
                .thenThrow(new IllegalArgumentException("Unknown control loop: cache-pool"));

        mockMvc.perform(post("/tuning/loops/cache-pool/enabled").param("value", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("rejected"))
                .andExpect(jsonPath("$.message").value("Unknown control loop: cache-pool"));
    }

    @Test
    void inspectingAnUnknownLoopIsRejectedRatherThanSurfacingAsAServerFault() throws Exception {
        when(controlLoop.loop("nope"))
                .thenThrow(new IllegalArgumentException("Unknown control loop: nope"));

        mockMvc.perform(get("/tuning/loops/nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown control loop: nope"));
    }

    @Test
    void aValidModeChangeIsAcceptedAndReportsThePreviousMode() throws Exception {
        when(tuningState.setMode(any(TuningMode.class))).thenReturn(TuningMode.SHADOW);
        when(tuningState.getMode()).thenReturn(TuningMode.ACTIVE);

        mockMvc.perform(post("/tuning/mode").param("value", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previous").value("shadow"))
                .andExpect(jsonPath("$.mode").value("active"))
                .andExpect(jsonPath("$.effectiveMode").value("active"));
    }
}